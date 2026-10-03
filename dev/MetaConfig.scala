// SPDX-License-Identifier: Apache-2.0

// Scala rewrite of the original Ansible meta-data.generic.j2 — much
// simpler than CloudConfig.scala/NetworkConfig.scala: just the two
// fields cloud-init actually reads out of meta-data. Meant to feed
// Task.WriteFile's `content` directly, same as those two.
//
// The original template had NO Jinja vars at all — it was the literal,
// static text `instance-id: iid-local24` / `local-hostname: cloudimg`
// for every VM, unlike user-data.j2/network-config.j2 which are both
// fully parameterized per-host. That's a real, known cloud-init
// footgun, not a harmless simplification: cloud-init only re-runs its
// per-instance setup (user-data) when it sees an instance-id it hasn't
// seen before — a second VM cloned from the same base image with the
// SAME instance-id as an earlier one can boot and find cloud-init
// silently skip user-data entirely, having already "seen" that id.
// `local-hostname` was static too, so every VM would report the same
// hostname to cloud-init's own internal bookkeeping regardless of what
// hostname the OS itself ends up with.
//
// Fixed here by taking both as real per-VM parameters instead of
// hardcoding them — `instanceId` defaults to `iid-$hostname` so it's
// at least unique per hostname without the caller having to invent
// one, but a genuinely fresh id (e.g. a UUID) per rebuild is safer
// still if the same hostname is ever reused across rebuilds.
object MetaConfig {

  def render(hostname: String, instanceId: String = ""): String =
    val id = if instanceId.isEmpty then s"iid-$hostname" else instanceId
    s"""instance-id: $id
       |local-hostname: $hostname
       |""".stripMargin
}
