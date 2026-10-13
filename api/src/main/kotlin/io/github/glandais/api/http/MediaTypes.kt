package io.github.glandais.api.http

import io.ktor.http.ContentType

/** Every media type the API reads or writes, spelled once. */
object MediaTypes {
    val Gpx: ContentType = ContentType("application", "gpx+xml")
    val Fit: ContentType = ContentType("application", "vnd.ant.fit")
    val Csv: ContentType = ContentType.Text.CSV
    val Columns: ContentType = ContentType("application", "vnd.vcyclist.columns+json")
    val Png: ContentType = ContentType.Image.PNG
    val ProblemJson: ContentType = ContentType("application", "problem+json")
}
