package org.matsim.other.Strategies;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.router.TripRouter;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityFacilityImpl;
import org.matsim.pt.transitSchedule.api.TransitRoute;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * STMStrategyModule
 *

 * - Valida pt-legs (exige TransitRoute com paradas);
 * - Normaliza mode/routingMode por trip;
 * - Faz fallback (mantém oldLeg) quando newTrip é inconsistente;
 * - Constrói nova lista de PlanElements para evitar loops;
 * - Lê parâmetros básicos do Config (módulo "STM").
 */
public class STMConfigGroup extends ConfigGroup {
    public static final String GROUP_NAME = "STM";

    private double alpha = 1.0;
    private double gamma = 1.0;
    private double omega = 10.0;
    private double beta = 10.0;
    private double delta = 1100.0;
    private List<Double> arrivalTargets = Arrays.asList(8*3600.0, 9*3600.0, 10*3600.0, 23*3600.0);
    private String stmInfoDir = "STM_INFO";
    public STMConfigGroup() {
        super(GROUP_NAME);
    }

    @Override
    public void addParam(String key, String value) {
        switch (key) {
            case "alpha":
                this.alpha = Double.parseDouble(value);
                break;
            case "gamma":
                this.gamma = Double.parseDouble(value);
                break;
            case "omega":
                this.omega = Double.parseDouble(value);
                break;
            case "beta":
                this.beta = Double.parseDouble(value);
                break;
            case "delta":
                this.delta = Double.parseDouble(value);
                break;
            case "arrivalTargets":
                this.arrivalTargets = Arrays.stream(value.split(","))
                        .map(String::trim)
                        .map(Double::parseDouble)
                        .collect(Collectors.toList());
                validateArrivalTargets();
                break;
            case "stmInfoDir":
                this.stmInfoDir = value;
                break;
            default:
                throw new IllegalArgumentException("Parâmetro desconhecido: " + key);
        }
    }

    @Override
    public String getValue(String key) {
        switch (key) {
            case "alpha":
                return String.valueOf(alpha);
            case "gamma":
                return String.valueOf(gamma);
            case "omega":
                return String.valueOf(omega);
            case "beta":
                return String.valueOf(beta);
            case "delta":
                return String.valueOf(delta);
            case "arrivalTargets":
                return arrivalTargets.stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(","));
            case "stmInfoDir":
                return stmInfoDir;
            default:
                return null;
        }
    }


    public java.util.Set<String> getParameterSetNames() {
        return java.util.Collections.emptySet();
    }

    // Getters
    public double getAlpha() {
        return alpha;
    }

    public double getGamma() {
        return gamma;
    }

    public double getOmega() {
        return omega;
    }

    public double getBeta() {
        return beta;
    }

    public double getDelta() {
        return delta;
    }

    public List<Double> getArrivalTargets() { return arrivalTargets; }

    public String getStmInfoDir() { return stmInfoDir; }

    // validação automática de tempo
    private void validateArrivalTargets() {
        for (Double t : arrivalTargets) {
            if (t < 0 || t > 86400) {
                throw new IllegalArgumentException("arrivalTargets deve estar entre 0 e 86400 segundos. Valor inválido: " + t);
            }
        }
        List<Double> sorted = new ArrayList<>(arrivalTargets);
        Collections.sort(sorted);
        if (!sorted.equals(arrivalTargets)) {
            throw new IllegalArgumentException("arrivalTargets deve estar em ordem crescente. Valores recebidos: " + arrivalTargets);
        }
    }
}
