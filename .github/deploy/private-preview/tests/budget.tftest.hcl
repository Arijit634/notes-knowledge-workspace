mock_provider "oci" {}
variables {
  compartment_id        = "ocid1.compartment.oc1..synthetic"
  home_region           = "ap-mumbai-1"
  availability_domain   = "synthetic:AP-MUMBAI-1-AD-1"
  ubuntu_arm64_image_id = "ocid1.image.oc1.ap-mumbai-1.synthetic"
  ssh_public_key        = "ssh-ed25519 synthetic-fixture-not-a-real-key"
  owner_ipv4_cidr       = "192.0.2.1/32"
}
run "unverified_entitlement_cannot_plan" {
  command         = plan
  expect_failures = [oci_core_vcn.preview]
}
run "restricted_free_plan" {
  command = plan
  variables { free_entitlement_verified = true }
  assert {
    condition     = oci_core_instance.preview.shape == "VM.Standard.A1.Flex" && oci_core_instance.preview.shape_config[0].ocpus == 2 && oci_core_instance.preview.shape_config[0].memory_in_gbs == 8
    error_message = "Only the bounded Always Free ARM shape is allowed."
  }
  assert {
    condition     = tonumber(oci_core_instance.preview.source_details[0].boot_volume_size_in_gbs) == 80
    error_message = "Boot storage must remain bounded within the independently checked free allowance."
  }
  assert {
    condition     = alltrue([for rule in oci_core_security_list.preview.ingress_security_rules : rule.protocol == "6" && contains([22, 80, 443], rule.tcp_options[0].min) && rule.tcp_options[0].min == rule.tcp_options[0].max && (rule.tcp_options[0].min == 80 || rule.source == "192.0.2.1/32")])
    error_message = "Only ACME HTTP is public; SSH/HTTPS remain owner-restricted before final launch approval."
  }
  assert {
    condition     = oci_objectstorage_bucket.preview.access_type == "NoPublicAccess" && oci_objectstorage_bucket.preview.storage_tier == "Standard" && oci_objectstorage_bucket.preview.versioning == "Disabled"
    error_message = "Private Standard storage only; no public objects or unbounded retained versions."
  }
}
