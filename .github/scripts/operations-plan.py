#!/usr/bin/env python3
"""Offline OCI Monitoring plan compiler. Never contacts OCI or enables an alarm.

validate [--scrape FILE]: validate source definitions, optionally bind them to a
real restricted exporter capture. render BINDINGS OUTPUT: create disabled OCI
alarm request files and seven Console panel query files for operator review.
Platform/operator queries must come from a dated, explicitly verified binding;
missing signals stay UNBOUND, not zero/healthy. No credential/config discovery.
"""
import argparse
import datetime
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT = ROOT / 'src/main/resources/operations/observability.json'


def require(condition, code):
    if not condition:
        raise ValueError(code)


def validate(data):
    require(data['format'] == 'oci-console-monitoring-plan-1', 'invalid_format')
    require(data['namespace'] == 'notes_workspace', 'invalid_namespace')
    require(data['scrape'] == dict(url='http://127.0.0.1:8081/actuator/prometheus', intervalSeconds=60, public=False), 'unsafe_scrape')
    require(data['limits']['additionalMonthlySpend'] == 0 and data['limits']['logRetentionApprovalRequired'], 'operator_gate_missing')
    require(len(data['events']) == len(set(data['events'])) == 17, 'event_contract_incomplete')
    require([r['id'] for r in data['dashboards']] == list(range(1, 8)), 'dashboard_contract_incomplete')
    require([r['id'] for r in data['alerts']] == list(range(1, 28)), 'alert_contract_incomplete')
    signals = data['applicationSignals'] | data['operatorSignals']
    for signal in data['applicationSignals'].values():
        require(re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_.]*', signal['meter']), 'invalid_meter')
        require(re.match(r'^[a-zA-Z_][a-zA-Z0-9_]*\[[0-9]+m\]', signal['query']), 'invalid_mql_metric')
    for dashboard in data['dashboards']:
        require(dashboard['signals'] and all(s in signals for s in dashboard['signals']), 'unknown_dashboard_signal')
    for alarm in data['alerts']:
        require(alarm['signal'] in signals and alarm['runbook'] and alarm['recovery'], 'invalid_alarm_binding')
        require(re.fullmatch(r'PT[0-9]+M', alarm['pending']) and alarm['severity'] in ('CRITICAL', 'ERROR', 'WARNING'), 'invalid_alarm_policy')
        if alarm['id'] >= 24:
            require(alarm['severity'] == 'CRITICAL' and not alarm['maintenanceAllowed'], 'invariant_suppression_forbidden')
        require('query' in alarm or 'condition' in alarm, 'alarm_condition_missing')
    return data


def bind(data, config):
    # This is an input whitelist, not a general JSON/config or cloud-secret reader.
    require(set(config) == {'compartmentId', 'notificationTopicId', 'verifiedOn', 'zeroCostConfirmed',
                            'retentionApproved', 'scrapeIntervalSeconds', 'signals', 'alarms'}, 'invalid_binding_fields')
    for key, kind in [('compartmentId', 'compartment'), ('notificationTopicId', 'onstopic')]:
        require(re.fullmatch(r'ocid1\.' + kind + r'\.[a-z0-9.]+', config[key]), 'invalid_operator_resource')
    date = datetime.date.fromisoformat(config['verifiedOn'])
    require(0 <= (datetime.date.today() - date).days <= 7, 'stale_operator_verification')
    require(config['zeroCostConfirmed'] is True and config['retentionApproved'] is True
            and config['scrapeIntervalSeconds'] == 60, 'operator_safety_gate_missing')
    require(set(config['signals']).issubset(data['operatorSignals']), 'unknown_external_signal')
    require(set(config['alarms']).issubset({str(n) for n in range(1, 28)}), 'unknown_alarm')
    for value in list(config['signals'].values()) + list(config['alarms'].values()):
        require(set(value) == {'namespace', 'query', 'verifiedAvailable'} and value['verifiedAvailable'] is True, 'unverified_external_signal')
        require(re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', value['namespace']), 'unsafe_external_namespace')
        require(isinstance(value['query'], str) and 0 < len(value['query']) <= 2048
                and '\n' not in value['query'] and re.match(r'^[A-Za-z_(]', value['query'])
                and not any(v in value['query'].lower() for v in ('http:', 'https:', 'token', 'password', 'secret')), 'unsafe_external_query')
    return config


def render(data, config, directory):
    # No overwrite, no deployment call, no inferred account identifiers.
    directory.mkdir(parents=True, exist_ok=False)
    # Oracle Unified Monitoring Agent URL source. Installation, dynamic-group
    # IAM, private-listener reachability and the measured 60s scrape cadence
    # require independent operator verification; Java holds no OCI credentials.
    allow = sorted({re.match(r'^([A-Za-z_][A-Za-z0-9_]*)', r['query']).group(1) for r in data['applicationSignals'].values()})
    agent = dict(configurationType='MONITORING', applicationConfigurations=[dict(sourceType='URL',
        source=dict(name='nkw-loopback', scrapeTargets=[dict(name='backend', url=data['scrape']['url'])]),
        destination=dict(compartmentId=config['compartmentId'], metricsNamespace=data['namespace']),
        filter=dict(name='nkw-bounded-series', filterType='URL_FILTER', allowList=allow, denyList=[]))])
    (directory / 'agent-monitoring.json').write_text(json.dumps(agent, indent=2) + '\n')
    missing = []
    for dashboard in data['dashboards']:
        panels = []
        for name in dashboard['signals']:
            value = data['applicationSignals'].get(name)
            binding = dict(namespace=data['namespace'], query=value['query']) if value else config['signals'].get(name)
            panels.append(dict(signal=name, status='BOUND' if binding else 'UNBOUND', **(binding or {})))
            if not binding:
                missing.append(name)
        (directory / ('dashboard-' + str(dashboard['id']) + '.json')).write_text(json.dumps(
            dict(format='oci-console-panel-queries-1', name=dashboard['name'], compartmentId=config['compartmentId'], panels=panels), indent=2) + '\n')
    for alarm in data['alerts']:
        override = config['alarms'].get(str(alarm['id']))
        query = override['query'] if override else alarm.get('query')
        if not query:
            missing.append('alarm-' + str(alarm['id']))
            continue
        payload = dict(compartmentId=config['compartmentId'], metricCompartmentId=config['compartmentId'],
            destinations=[config['notificationTopicId']], displayName='NKW ' + alarm['name'],
            namespace=override['namespace'] if override else data['namespace'], queryText=query,
            pendingDuration=alarm['pending'], resolution='1m', severity=alarm['severity'],
            isEnabled=False, repeatNotificationDuration='PT1H', isNotificationsPerMetricDimensionEnabled=False,
            body='Runbook: ' + alarm['runbook'] + '. Recovery: ' + alarm['recovery'] + '. No private data in notifications.')
        (directory / ('alarm-' + str(alarm['id']) + '.json')).write_text(json.dumps(payload, indent=2) + '\n')
    receipt = dict(dashboards=7, logicalAlerts=27, events=17, unbound=sorted(set(missing)),
                   configurationBound=not missing, productionReady=False, deploymentAuthorized=False, cloudCalls=0,
                   consolePanelFormat='Query files for Console Metric Explorer; not a guessed Console widget schema',
                   alarmFormat='oci monitoring alarm create --from-json file://alarm-N.json; disabled until separate approval')
    (directory / 'operations-plan.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('validate', 'render'))
    parser.add_argument('--bindings', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--scrape', type=pathlib.Path)
    args = parser.parse_args()
    data = validate(json.loads(CONTRACT.read_text()))
    receipt = dict(definitionValid=True, dashboards=7, logicalAlerts=27, events=17, cloudCalls=0)
    if args.scrape:
        # Only the metric NAME set escapes this boundary, never sample values/labels.
        names = set(re.findall(r'^([A-Za-z_:][A-Za-z0-9_:]*)[ {]', args.scrape.read_text(), re.MULTILINE))
        expected = {re.match(r'^([A-Za-z_][A-Za-z0-9_]*)', r['query']).group(1) for r in data['applicationSignals'].values()}
        receipt['observedMetricNames'] = sorted(names & expected)
        receipt['notObservedYet'] = sorted(expected - names)
        receipt['allReferencedApplicationMetricsObserved'] = expected.issubset(names)
    if args.mode == 'render':
        require(args.bindings is not None and args.output is not None, 'explicit_binding_and_output_required')
        receipt = render(data, bind(data, json.loads(args.bindings.read_text())), args.output)
    print(json.dumps(receipt))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, TypeError):
        raise SystemExit('operations_plan_invalid; no cloud action performed') from None
