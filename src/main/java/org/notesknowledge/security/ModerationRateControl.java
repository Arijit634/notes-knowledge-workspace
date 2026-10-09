package org.notesknowledge.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Fail closed; network and aggregate buckets bound multi-account/mass-action abuse. */
@Component
public class ModerationRateControl {
    private final RateControlService control;
    private final RateKeyDeriver keys;
    ModerationRateControl(RateControlService control,RateKeyDeriver keys){this.control=control;this.keys=keys;}
    public void intake(HttpServletRequest request,UUID actor){check("REPORT",actor.toString());check("REPORT_NETWORK",request.getRemoteAddr());check("REPORT_GLOBAL","aggregate");}
    public void review(HttpServletRequest request,UUID actor,boolean mutation){check(mutation?"MODERATION_ACTION":"MODERATION_READ",actor.toString());check("MODERATION_GLOBAL","aggregate");}
    private void check(String name,String subject){control.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(name),keys.derive(name,subject),1),RateControlService.Policy.SECURITY_CRITICAL);}
}
