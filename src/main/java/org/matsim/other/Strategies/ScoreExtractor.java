package org.matsim.other.Strategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ScoreExtractor {

    public static double extractFinalScore(
            String outputDirectory
    ) {

        try {

            Path file = Path.of(
                    outputDirectory,
                    "scorestats.csv"
            );

            List<String> lines =
                    Files.readAllLines(file);

            String last =
                    lines.get(lines.size() - 1);

            String[] columns =
                    last.split(";");

            return Double.parseDouble(
                    columns[1]
            );

        } catch (IOException e) {

            throw new RuntimeException(e);
        }
    }
}