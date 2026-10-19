// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator.conf

import io.circe.yaml.parser
import io.circe.{Json, JsonObject}

// SPIKE. Reads the output of `oslo-config-generator --format json`
// (manifests/dump_oslo_options.sh) into a `Catalogue`.
//
// The document is `{"options": {<section>: {"opts": [<option>, ...]}}, ...}`.
// An option has `name`, `type` (oslo's own description, e.g. "string value",
// "integer value", "boolean value"), `default`, `choices` (pairs of value and
// description), `secret` and `deprecated_opts` (pairs of group and old name).
//
// JSON is valid YAML, and this module already has a YAML parser (it has no
// circe-parser), so the YAML parser reads it.
object OsloJson:

  def load(
      service: String,
      text: String,
      openSections: Set[String] = Set.empty
  ): Either[String, Catalogue] =
    for
      doc <- parser
        .parse(text)
        .left
        .map(e => s"not valid JSON: ${e.getMessage}")
      sections <- doc.hcursor
        .downField("options")
        .as[JsonObject]
        .left
        .map(_ =>
          "no \"options\" object: is this oslo-config-generator --format json output?"
        )
    yield Catalogue(
      service,
      // The same option can come from two namespaces; the first is kept.
      sections.toList.flatMap(options).distinctBy(o => (o.section, o.name)),
      openSections
    )

  private def options(section: (String, Json)): List[OptionSpec] =
    val (group, body) = section
    body.hcursor
      .downField("opts")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap(option(group, _))

  private def option(section: String, j: Json): Option[OptionSpec] =
    val c = j.hcursor
    c.get[String]("name").toOption.map { name =>
      val choices = c
        .downField("choices")
        .as[List[Json]]
        .getOrElse(Nil)
        .flatMap(x =>
          x.asArray.flatMap(_.headOption).orElse(Some(x)).flatMap(scalar)
        )
      val tpe = c.get[String]("type").getOrElse("")
      OptionSpec(
        section = section,
        name = name,
        tpe =
          if choices.nonEmpty then OptType.Choice(choices) else optType(tpe),
        default = c.downField("default").focus.flatMap(scalar),
        // Only names in the same section: the catalogue finds an old name by
        // (section, name). A move to another section is not looked up.
        deprecated = c
          .downField("deprecated_opts")
          .as[List[Json]]
          .getOrElse(Nil)
          .flatMap(d =>
            d.hcursor
              .get[String]("name")
              .toOption
              .filter(_ =>
                d.hcursor.get[String]("group").toOption.contains(section)
              )
          ),
        secret = c.get[Boolean]("secret").getOrElse(false)
      )
    }

  /** oslo's description of a type to ours. The word "list" or "dict" comes
    * first in oslo's text for those, so it is tested before the scalar words.
    */
  def optType(oslo: String): OptType =
    val t = oslo.toLowerCase
    if t.startsWith("list") then OptType.Items
    else if t.startsWith("dict") then OptType.Dict
    else if t.startsWith("string") || t.startsWith("uri") then OptType.Text
    else if t.startsWith("integer") then OptType.Integer
    else if t.startsWith("boolean") then OptType.Flag
    else if t.startsWith("floating") then OptType.Real
    else if t.startsWith("port") then OptType.PortNumber
    else OptType.Other(oslo)

  private def scalar(j: Json): Option[String] =
    j.asString
      .orElse(j.asNumber.map(_.toString))
      .orElse(j.asBoolean.map(_.toString))
      .orElse(j.asArray.map(_.flatMap(scalar).mkString(",")))
