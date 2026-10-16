# PlantUML

There are two PlantUML exporters in this package - [StructurizrPlantUMLExporter](StructurizrPlantUMLExporter.java) and [C4PlantUMLExporter](C4PlantUMLExporter.java).

## Icons

Both exporters support PNG, JPEG, and SVG icons from HTTP(S) URLs or data URIs, which contain the image within the reference.
The DSL parser and local theme loader convert local icon files to data URIs before export.
For C4-PlantUML, set the `c4plantuml.tags` view or view set property to `true` to include styled icons.

The exporters convert SVG icons to embedded PNG images by default.
The PNG images retain transparency and aspect ratio, with four times the displayed width and height to support high-DPI PNG rendering.
PNG and JPEG icons retain their original formats.
Icons are prepared once per size within each diagram and reused in its legend.

PlantUML 1.2026.5 preserves embedded SVG icons in SVG output but omits them from PNG output.
The default conversion makes the icons visible in both output formats.
SVG diagrams then contain raster icons, which lose sharpness at sufficiently high zoom levels.
PlantUML 1.2026.5 downsamples these PNG icons to their displayed dimensions when embedding them in SVG output.

To preserve SVG vectors, add `-preserveSvgIcons` to the Structurizr `export` command and render the `.puml` files with `plantuml -tsvg`.
Java callers can use `exporter.setPreserveSvgIcons(true)` for the same result.
This option applies to both PlantUML exporters and does not change the DSL.

The exporters read SVG dimensions from `width`, `height`, or `viewBox`.
SVG lengths can use pixels or absolute units such as `pt`, `mm`, and `in`.
Relative SVG lengths use the `viewBox` dimensions when available.
SVG conversion disables scripts and external resources and reports an error if conversion fails.
