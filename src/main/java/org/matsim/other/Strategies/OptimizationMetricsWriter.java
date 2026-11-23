package org.matsim.other.Strategies;

import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

public class OptimizationMetricsWriter
        implements ShutdownListener {

    @Override
    public void notifyShutdown(
            ShutdownEvent event
    ) {

        try {

            String outputDir =

                    event
                            .getServices()
                            .getControlerIO()
                            .getOutputPath();

            FitnessMetrics metrics =

                    CsvMetricsExtractor
                            .extract(
                                    outputDir
                            );

            FitnessWriter.write(
                    outputDir,
                    metrics
            );

            System.out.println(
                    "fitness.json gerado"
            );

        } catch(Exception e) {

            e.printStackTrace();
        }
    }
}