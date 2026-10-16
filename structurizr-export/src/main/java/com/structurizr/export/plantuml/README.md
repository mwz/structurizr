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
Image preparation reads each icon once and retains its dimensions, so remote PNG and JPEG icons do not require a second download.
An explicit `c4plantuml.sprite` property takes precedence over the style icon.

PlantUML 1.2026.5 preserves embedded SVG icons in SVG output but omits them from PNG output.
The default conversion makes the icons visible in both output formats.
SVG diagrams then contain raster icons, which lose sharpness at sufficiently high zoom levels.
PlantUML 1.2026.5 downsamples these PNG icons to their displayed dimensions when embedding them in SVG output.

To preserve SVG vectors, add `-preserveSvgIcons` to the Structurizr `export` command and render the `.puml` files with `plantuml -tsvg`.
Java callers can use `exporter.setPreserveSvgIcons(true)` for the same result.
This option applies to both PlantUML exporters and does not change the DSL.

The exporters read SVG dimensions from `width`, `height`, or `viewBox`.
SVG root dimensions can use pixels or absolute units such as `pt`, `mm`, and `in`.
Relative SVG lengths use the `viewBox` dimensions when available.
The exporters convert root dimensions to pixels before processing SVG icons, including lengths in `Q` (quarter-millimetres).
Preserved SVG icons use an outer SVG element with dimensions in pixels, which protects the original `viewBox` and root styles during PlantUML rendering.
SVG data URIs can contain Base64, raw XML, or percent-encoded XML.
Raw XML retains literal percentage signs and plus signs.

If an icon download fails or AWT image support is unavailable, export continues with the original reference at scale 0.5.
A warning identifies the affected style, and the final renderer must load the original icon.
SVG conversion disables scripts and external resources.
Other conversion failures stop export with an error that identifies the affected style.
