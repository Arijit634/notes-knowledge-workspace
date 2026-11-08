terraform {
  required_version = "= 1.17.1"
  required_providers {
    oci = { source = "oracle/oci", version = "9.12.1" }
  }
}
provider "oci" { region = var.home_region }

variable "compartment_id" { type = string }
variable "home_region" { type = string }
variable "availability_domain" { type = string }
variable "ubuntu_arm64_image_id" { type = string }
variable "ssh_public_key" { type = string }
variable "owner_ipv4_cidr" {
  type      = string
  sensitive = true
  validation {
    condition     = can(cidrhost(var.owner_ipv4_cidr, 0)) && endswith(var.owner_ipv4_cidr, "/32")
    error_message = "One operator IPv4 /32 is required."
  }
}
variable "free_entitlement_verified" {
  type        = bool
  default     = false
  description = "Operator must first verify home region, Always Free shape/image, remaining 2 OCPU/12 GB, 80 GB block and object allowances. Trial credits do not establish entitlement."
}
variable "public_launch_approved" {
  type        = bool
  default     = false
  description = "Separate final owner approval after hosted security, persistence and recovery qualification."
}

resource "oci_core_vcn" "preview" {
  compartment_id = var.compartment_id
  cidr_blocks    = ["10.88.0.0/16"]
  display_name   = "nkw-preview"
  dns_label      = "nkwpreview"
  lifecycle {
    precondition {
      condition     = var.free_entitlement_verified
      error_message = "No provisioning without verified Always Free account capacity. No paid or trial-credit fallback."
    }
  }
}
resource "oci_core_internet_gateway" "preview" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.preview.id
  display_name   = "nkw-preview"
  enabled        = true
}
resource "oci_core_route_table" "preview" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.preview.id
  route_rules {
    network_entity_id = oci_core_internet_gateway.preview.id
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
  }
}
resource "oci_core_security_list" "preview" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.preview.id
  display_name   = "nkw-preview"
  egress_security_rules {
    protocol    = "all"
    destination = "0.0.0.0/0"
  }
  ingress_security_rules {
    protocol = "6"
    source   = var.owner_ipv4_cidr
    tcp_options {
      min = 22
      max = 22
    }
  }
  ingress_security_rules {
    protocol = "6"
    source   = "0.0.0.0/0"
    tcp_options {
      min = 80
      max = 80
    }
  }
  ingress_security_rules {
    protocol = "6"
    source   = var.public_launch_approved ? "0.0.0.0/0" : var.owner_ipv4_cidr
    tcp_options {
      min = 443
      max = 443
    }
  }
}
resource "oci_core_subnet" "preview" {
  compartment_id    = var.compartment_id
  vcn_id            = oci_core_vcn.preview.id
  cidr_block        = "10.88.1.0/24"
  route_table_id    = oci_core_route_table.preview.id
  security_list_ids = [oci_core_security_list.preview.id]
  dns_label         = "app"
}
resource "oci_core_instance" "preview" {
  compartment_id      = var.compartment_id
  availability_domain = var.availability_domain
  display_name        = "nkw-preview"
  shape               = "VM.Standard.A1.Flex"
  shape_config {
    ocpus         = 2
    memory_in_gbs = 8
  }
  source_details {
    source_type             = "image"
    source_id               = var.ubuntu_arm64_image_id
    boot_volume_size_in_gbs = 80
  }
  create_vnic_details {
    subnet_id        = oci_core_subnet.preview.id
    assign_public_ip = true
  }
  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(file("${path.module}/cloud-init.yaml"))
  }
  lifecycle { prevent_destroy = true }
}
data "oci_objectstorage_namespace" "preview" { compartment_id = var.compartment_id }
resource "oci_objectstorage_bucket" "preview" {
  compartment_id = var.compartment_id
  namespace      = data.oci_objectstorage_namespace.preview.namespace
  name           = "nkw-preview-${substr(sha256(var.compartment_id), 0, 12)}"
  access_type    = "NoPublicAccess"
  storage_tier   = "Standard"
  versioning     = "Disabled"
  auto_tiering   = "Disabled"
  lifecycle { prevent_destroy = true }
}
output "instance_id" { value = oci_core_instance.preview.id }
output "public_ipv4" { value = oci_core_instance.preview.public_ip }
output "bucket_name" { value = oci_objectstorage_bucket.preview.name }
output "namespace" { value = data.oci_objectstorage_namespace.preview.namespace }
