package org.matsim.other.Strategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CsvMetricsExtractor {

    public static FitnessMetrics extract(
            String outputDirectory
    ) {

        FitnessMetrics metrics =
                new FitnessMetrics();

        metrics.avgScore =
                readLastNumericValue(
                        Path.of(
                                outputDirectory,
                                "scorestats.csv"
                        )
                );

        metrics.ptShare =
                readPtShare(
                        Path.of(
                                outputDirectory,
                                "modestats.csv"
                        )
                );

        metrics.vehicleKm =
                readLastNumericValue(
                        Path.of(
                                outputDirectory,
                                "pkm_modestats.csv"
                        )
                );

        /*
         * Valores inicialmente estimados
         * até implementar cálculo direto
         */

        metrics.avgTravelTimeMin =
                40.0;

        metrics.avgWaitTimeMin =
                5.0;

        metrics.vehicleHours =
                metrics.vehicleKm / 20.0;

        metrics.fleetSize =
                50;

        metrics.avgOccupancy =
                35;

        double costPerKm =
                7.5;

        double costPerHour =
                120.0;

        metrics.operationalCost =

                metrics.vehicleKm
                        * costPerKm

                        +

                        metrics.vehicleHours
                                * costPerHour;

        return metrics;
    }

    private static double readLastNumericValue(
            Path file
    ) {

        try {

            List<String> lines =
                    Files.readAllLines(file);

            if (lines.size() < 2)
                return 0;

            String last =
                    lines.get(
                            lines.size() - 1
                    );

            String[] columns =
                    last.split(";");

            return Double.parseDouble(
                    columns[
                            columns.length - 1
                            ].replace(",", ".")
            );

        } catch(Exception e) {

            return 0;
        }
    }

    private static double readPtShare(
            Path file
    ) {

        try {

            List<String> lines =
                    Files.readAllLines(file);

            String last =
                    lines.get(
                            lines.size()-1
                    );

            String[] cols =
                    last.split(";");

            for(String c : cols) {

                try {

                    double v =
                            Double.parseDouble(
                                    c.replace(",", ".")
                            );

                    if(v >= 0 && v <= 1)
                        return v;

                } catch(Exception ignored) {}
            }

        } catch(Exception ignored) {}

        return 0;
    }
}