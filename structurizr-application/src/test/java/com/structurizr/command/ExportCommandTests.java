package com.structurizr.command;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExportCommandTests {

    @TempDir
    Path directory;

    @Test
    public void run() throws Exception {
        File tmpDir = Files.createTempDirectory("structurizr").toFile();

        String[] args = {
                "export",
                "-workspace", "src/test/dsl/workspace.dsl",
                "-output", tmpDir.getCanonicalPath(),
                "-format", "plantuml"
        };
        new ExportCommand().run(args);

        assertTrue(new File(tmpDir, "structurizr-SystemLandscape.puml").exists());
    }

    @Test
    void svgIconsAreRasterisedUnlessPreservationIsRequested() throws Exception {
        testSvgIcons("plantuml");
        testSvgIcons("plantuml/c4plantuml");
    }

    private void testSvgIcons(String format) throws Exception {
        Files.writeString(directory.resolve("icon.svg"), "<svg xmlns='http://www.w3.org/2000/svg' width='120' height='60'><rect width='120' height='60' fill='red'/></svg>");
        Path workspace = directory.resolve("workspace.dsl");
        Files.writeString(workspace, """
                workspace {
                    model {
                        s = softwareSystem "System"
                    }
                    views {
                        systemContext s icons {
                            include *
                        }
                        styles {
                            element "Software System" {
                                icon icon.svg
                            }
                        }
                        properties {
                            c4plantuml.tags true
                        }
                    }
                }
                """);
        ExportCommand command = new ExportCommand();
        // Run preservation first to verify that the next invocation resets the cached exporter configuration.
        Path vector = directory.resolve("vector");
        assertTrue(command.run("-w", workspace.toString(), "-f", format, "-o", vector.toString(), "-preserveSvgIcons"));
        assertTrue(Files.readString(vector.resolve("structurizr-icons.puml")).contains("img:data:image/svg+xml;base64,"));
        Path raster = directory.resolve("raster");
        assertTrue(command.run("-w", workspace.toString(), "-f", format, "-o", raster.toString()));
        String definition = Files.readString(raster.resolve("structurizr-icons.puml"));
        assertTrue(definition.contains("img:data:image/png;base64,"));
        assertFalse(definition.contains("img:data:image/svg+xml;"));
    }

    @Test
    void preserveSvgIconsRequiresPlantUML() throws Exception {
        assertFalse(new ExportCommand().run("-f", "mermaid", "-preserveSvgIcons"));
    }

    @Test
    void unavailableIconDoesNotPreventAnyDiagramFromBeingWritten() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/icon.png", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            Path workspace = directory.resolve("workspace.dsl");
            Files.writeString(workspace, """
                    workspace {
                        model {
                            s = softwareSystem "System"
                        }
                        views {
                            systemLandscape landscape {
                                include *
                            }
                            systemContext s context {
                                include *
                            }
                            styles {
                                element "Software System" {
                                    icon http://127.0.0.1:%d/icon.png
                                }
                            }
                            properties {
                                c4plantuml.tags true
                            }
                        }
                    }
                    """.formatted(server.getAddress().getPort()));
            for (String format : new String[] {"plantuml", "plantuml/c4plantuml"}) {
                Path output = directory.resolve(format.replace('/', '-'));
                assertTrue(new ExportCommand().run("-w", workspace.toString(), "-f", format, "-o", output.toString()));
                assertTrue(Files.exists(output.resolve("structurizr-landscape.puml")));
                assertTrue(Files.exists(output.resolve("structurizr-context.puml")));
            }
        } finally {
            server.stop(0);
        }
    }

}
