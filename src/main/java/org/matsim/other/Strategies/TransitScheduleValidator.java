package org.matsim.other.Strategies;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class TransitScheduleValidator {

    public static void main(String[] args) {
        // Ajuste os caminhos conforme sua estrutura
        String configPath = "scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config.xml";
        String schedulePath = "scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/schedule.xml";
        String networkPath = "scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/network.xml";

        // Carrega config e cenário
        Config config = ConfigUtils.loadConfig(configPath);
        Scenario scenario = ScenarioUtils.loadScenario(config);

        // Lê transit schedule
        new org.matsim.pt.transitSchedule.TransitScheduleReaderV1(scenario).readFile(schedulePath);

        // Valida cada stopFacility
        for (TransitStopFacility stop : scenario.getTransitSchedule().getFacilities().values()) {
            if (scenario.getNetwork().getLinks().get(stop.getLinkId()) == null) {
                System.err.println("ERRO: StopFacility " + stop.getId() +
                        " referencia link inexistente: " + stop.getLinkId());
            } else {
                System.out.println("OK: StopFacility " + stop.getId() +
                        " vinculado ao link " + stop.getLinkId());
            }
        }
    }
}
