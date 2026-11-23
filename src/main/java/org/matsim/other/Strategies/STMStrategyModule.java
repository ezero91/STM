package org.matsim.other.Strategies;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.population.PopulationUtils;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.router.TripRouter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.facilities.Facility;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.routes.TransitPassengerRoute;


import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

public class STMStrategyModule {

    private final TripRouter tripRouter;
    private final STMConfigGroup stmConfig;
    private final Scenario scenario;
    private final AlternativeTripGenerator altGenerator;
    private final StmDataProvider stmData;
    private final TransitOccupancyAnalyzer occupancyAnalyzer;
    private double delta;
    // variáveis globais da simulação
    private int totalVerificadosGlobal = 0;
    private int totalAceitosGlobal = 0;
    private int totalRejeitadosGlobal = 0;

    private PrintWriter fileLogger = null;

    public STMStrategyModule(TripRouter tripRouter, Config config, Scenario scenario, StmDataProvider stmData, TransitOccupancyAnalyzer occupancyAnalyzer) {
        this.tripRouter = tripRouter;
        this.stmConfig = (STMConfigGroup) config.getModules().get(STMConfigGroup.GROUP_NAME);
        this.scenario = scenario;
        this.occupancyAnalyzer = occupancyAnalyzer;
        this.altGenerator = new AlternativeTripGenerator(scenario, tripRouter, occupancyAnalyzer);
        this.stmData = stmData;
    }

    private void log(String msg) {
        System.out.println(msg);
        if (fileLogger != null) fileLogger.println(msg);
    }

    private void error(String msg) {
        System.err.println(msg);
        if (fileLogger != null) fileLogger.println("ERROR: " + msg);
    }


    private TransitRoute resolveTransitRoute(Leg ptLeg) {
        if (!(ptLeg.getRoute() instanceof TransitPassengerRoute)) return null;
        TransitPassengerRoute r = (TransitPassengerRoute) ptLeg.getRoute();
        TransitLine line =
                scenario.getTransitSchedule().getTransitLines().get(r.getLineId());
        return (line == null) ? null : line.getRoutes().get(r.getRouteId());
    }

    // Estrutura auxiliar
    public class PlanInfo {
        private final int index;
        private final String type;
        private final Id<Link> linkId;
        private final double startTime;
        private final double endTime;

        public PlanInfo(int index, String type, Id<Link> linkId, double startTime, double endTime) {
            this.index = index;
            this.type = type;
            this.linkId = linkId;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public int getIndex() {
            return index;
        }

        public String getType() {
            return type;
        }

        public Id<Link> getLinkId() {
            return linkId;
        }

        public double getStartTime() {
            return startTime;
        }

        public double getEndTime() {
            return endTime;
        }

        @Override
        public String toString() {
            return "PlanInfo[index=" + index +
                    ", type=" + type +
                    ", link=" + linkId +
                    ", start=" + startTime +
                    ", end=" + endTime + "]";
        }
    }


    private Map<Plan, Double> calculateAlternativeProbabilities(Person person, List<Plan> alternatives, double alpha, double gamma) {

        log("[LOG] Iniciando cálculo de probabilidades para pessoa " + person.getId() +
                " com alpha=" + alpha + " gamma=" + gamma);

        Map<Plan, Double> probabilities = new LinkedHashMap<>();

        for (Plan altPlan : alternatives) {
            log("[LOG] Avaliando alternativa...");
            for (PlanElement pe : altPlan.getPlanElements()) {
                if (pe instanceof Leg) {
                    Leg leg = (Leg) pe;
                    log("[LOG] Leg encontrado: modo=" + leg.getMode() +
                            " origem=" + leg.getRoute().getStartLinkId() +
                            " destino=" + leg.getRoute().getEndLinkId());
                } else if (pe instanceof Activity) {
                    Activity act = (Activity) pe;
                    log("[LOG] Activity encontrada: tipo=" + act.getType() +
                            " coord=" + act.getCoord());
                }
            }
            // supondo que cada alternativa tem um leg principal de transporte público
            Leg ptLeg = altPlan.getPlanElements().stream()
                    .filter(pe -> pe instanceof Leg)
                    .map(pe -> (Leg) pe)
                    .filter(l -> !"car".equals(l.getMode()) && !"walk".equals(l.getMode()))
                    .findFirst()
                    .orElse(null);

            if (ptLeg == null) {
                log("[LOG] Nenhum leg de transporte público encontrado nesta alternativa.");
                continue;
            }

            // número de horários de embarque disponíveis (exemplo simplificado)
            int departures = getNumberOfDepartures(ptLeg);
            log("[LOG] Iniciando cálculo de probabilidades para pessoa " + departures);

            // carga da rota por veículo (exemplo simplificado)
            double loading = getRouteLoading(ptLeg);
            log("[LOG] loading calculado = " + loading);

            // probabilidade = departures / loading
            double probability = (loading > 0) ? (double) Math.pow(departures, alpha) / Math.pow(loading, gamma) : 0.0;
            log("[LOG] departures=" + departures + " loading=" + loading +
                    " alpha=" + alpha + " gamma=" + gamma +
                    " prob=" + probability);

            System.out.println("Alternativa para pessoa " + person.getId() +
                    " tem probabilidade " + probability);
            probabilities.put(altPlan, probability);
        }
        log("[LOG] Finalizado cálculo de probabilidades. Total de alternativas avaliadas: " +
                probabilities.size());
        return probabilities;
    }

    private double getRouteLoading(Leg ptLeg) {
        if (!(ptLeg.getRoute() instanceof TransitPassengerRoute)) {
            log("[LOG] Leg não possui TransitPassengerRoute válido, retornando loading=1.0");
            return 1.0;
        }
        TransitPassengerRoute pr = (TransitPassengerRoute) ptLeg.getRoute();
        double load = stmData.getLoading(pr.getAccessStopId(), ptLeg.getDepartureTime().orElse(0.0));
        log("[LOG] loading(SUNT/sim) parada=" + pr.getAccessStopId() + " load=" + load);
        return load;
    }

    private int getNumberOfDepartures(Leg ptLeg) {
        if (!(ptLeg.getRoute() instanceof TransitPassengerRoute)) {
            log("[LOG] Leg não possui TransitPassengerRoute, retornando departures=0");
            return 0;
        }
        TransitPassengerRoute pr = (TransitPassengerRoute) ptLeg.getRoute();
        int n = stmData.getNRoutes(pr.getAccessStopId());
        log("[LOG] n_routes(SUNT) parada=" + pr.getAccessStopId() + " = " + n);
        return n;
    }

    private Plan selectPlanStochastically(Map<Plan, Double> probabilities, Plan originalPlan) {
        log("[LOG] Iniciando seleção estocástica...");

        if (probabilities == null || probabilities.isEmpty()) {
            log("[LOG] Nenhuma alternativa encontrada. Retornando plano original.");
            return originalPlan;
        }

        // soma total das probabilidades
        double total = probabilities.values().stream().mapToDouble(Double::doubleValue).sum();
        log("[LOG] Soma total das probabilidades = " + total);

        if (total <= 0) {
            // fallback: retorna o primeiro plano
            log("[LOG] Probabilidades somaram <= 0. Retornando plano original.");
            return originalPlan;
        }

        // sorteia um número entre 0 e total
        double rand = org.matsim.core.gbl.MatsimRandom.getRandom().nextDouble() * total;
        log("[LOG] Número aleatório sorteado = " + rand);

        double cumulative = 0.0;
        for (Map.Entry<Plan, Double> entry : probabilities.entrySet()) {
            cumulative += entry.getValue();
            log("[LOG] Plano " + entry.getKey() + " prob=" + entry.getValue() +
                    " cumulativo=" + cumulative);
            if (rand <= cumulative) {
                log("[LOG] Selecionado plano com probabilidade " + entry.getValue());
                return entry.getKey();
            }
        }

        // fallback: último plano
        log("[LOG] Nenhum plano selecionado no loop. Retornando último plano.");
        return probabilities.keySet().iterator().next();
    }

    private List<? extends PlanElement> recalcLeg(Leg oldLeg, Activity origin, Activity destination, Person person) {
        return tripRouter.calcRoute(
                oldLeg.getMode(),
                null, // origem facility
                null, // destino facility
                0.0,
                person,
                person.getAttributes()
        );
    }

    private boolean verifyTrip(List<? extends PlanElement> newTrip) {
        return newTrip != null && !newTrip.isEmpty();
    }

    private String planSignature(Plan p) {
        StringBuilder sb = new StringBuilder();
        for (PlanElement pe : p.getPlanElements()) {
            if (pe instanceof Activity) {
                Activity a = (Activity) pe;
                sb.append("A:").append(a.getType()).append("@").append(a.getLinkId()).append(";");
            } else if (pe instanceof Leg) {
                Leg l = (Leg) pe;
                sb.append("L:").append(l.getMode());
                if (l.getRoute() instanceof TransitPassengerRoute) {
                    TransitPassengerRoute r = (TransitPassengerRoute) l.getRoute();
                    sb.append("|").append(r.getLineId()).append("/").append(r.getRouteId())
                            .append("/").append(r.getAccessStopId()).append("->").append(r.getEgressStopId());
                }
                sb.append(";");
            }
        }
        return sb.toString();
    }

    // Estrutura auxiliar para guardar informações de cada Activity
    class ActivityInfo {
        int index;              // posição da activity no vetor
        String type;            // tipo da activity (home, work, school, etc.)
        double startTime;       // início da activity
        double endTime;         // fim da activity

        ActivityInfo(int index, String type, double startTime, double endTime) {
            this.index = index;
            this.type = type;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        @Override
        public String toString() {
            return "ActivityInfo[index=" + index + ", type=" + type +
                    ", start=" + startTime + ", end=" + endTime + "]";
        }
    }

    // Estrutura auxiliar para pares de atividades consecutivas
    class ActivityPair {
        ActivityInfo from;   // atividade de origem
        ActivityInfo to;     // atividade de destino

        ActivityPair(ActivityInfo from, ActivityInfo to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return "Pair[from=" + from.type + " (end=" + from.endTime + ")" +
                    " -> to=" + to.type + " (start=" + to.startTime + ")]";
        }
    }

    private Set<Plan> filterNearbyPlans_home(
            Plan chosenPlan,
            Plan originalPlan,
            List<Double> arrivalTargets,
            double omegaMinutes
    ) {
        log("[LOG] Iniciando filterNearbyPlans...");
        double omegaSeconds = omegaMinutes * 60.0;
        log("[LOG] Tolerância temporal definida: omegaMinutes=" + omegaMinutes + " -> omegaSeconds=" + omegaSeconds);

        Set<Plan> nearbyPlans = new LinkedHashSet<>();

        // --- Extrai activities relevantes do plano original ---
        log("[LOG] Extraindo activities do originalPlan...");
        List<ActivityInfo> origActs = new ArrayList<>();
        List<PlanElement> origElems = originalPlan.getPlanElements();
        for (int i = 0; i < origElems.size(); i++) {
            if (origElems.get(i) instanceof Activity) {
                Activity act = (Activity) origElems.get(i);
                double start = act.getStartTime().orElse(-1.0);
                double end = act.getEndTime().orElse(-1.0);
                if (start < 0 && i > 0 && origElems.get(i - 1) instanceof Leg) {
                    Leg prev = (Leg) origElems.get(i - 1);
                    double dep = prev.getDepartureTime().orElse(-1.0);
                    double dur = prev.getTravelTime().orElse(0.0);
                    if (dep >= 0 && dur >= 0) start = dep + dur;
                }
                if (end < 0 && i + 1 < origElems.size() && origElems.get(i + 1) instanceof Leg) {
                    Leg next = (Leg) origElems.get(i + 1);
                    double dep = next.getDepartureTime().orElse(-1.0);
                    if (dep >= 0) end = dep;
                }
                ActivityInfo ai = new ActivityInfo(i, act.getType(), start, end);
                origActs.add(ai);
                log("[LOG] Original activity ajustada: " + ai);
            }
        }
        List<ActivityInfo> importantActs = origActs.stream()
                .filter(a -> a.type.equals("home") || a.type.equals("work") || a.type.equals("school"))
                .collect(Collectors.toList());
        List<ActivityPair> importantPairs = new ArrayList<>();
        for (int i = 0; i < importantActs.size() - 1; i++) {
            importantPairs.add(new ActivityPair(importantActs.get(i), importantActs.get(i + 1)));
        }
        log("[LOG] Pares importantes (original): " + importantPairs);
        // --- Extrai activities relevantes do chosenPlan ---
        List<ActivityInfo> chosenActs = new ArrayList<>();
        List<PlanElement> chosenElems = chosenPlan.getPlanElements();
        for (int i = 0; i < chosenElems.size(); i++) {
            if (chosenElems.get(i) instanceof Activity) {
                Activity act = (Activity) chosenElems.get(i);
                double start = act.getStartTime().orElse(-1.0);
                double end = act.getEndTime().orElse(-1.0);

                if (start < 0 && i > 0 && chosenElems.get(i - 1) instanceof Leg) {
                    Leg prev = (Leg) chosenElems.get(i - 1);
                    double dep = prev.getDepartureTime().orElse(-1.0);
                    double dur = prev.getTravelTime().orElse(0.0);
                    if (dep >= 0 && dur >= 0) start = dep + dur;
                }
                if (end < 0 && i + 1 < chosenElems.size() && chosenElems.get(i + 1) instanceof Leg) {
                    Leg next = (Leg) chosenElems.get(i + 1);
                    double dep = next.getDepartureTime().orElse(-1.0);
                    if (dep >= 0) end = dep;
                }

                ActivityInfo ai = new ActivityInfo(i, act.getType(), start, end);
                chosenActs.add(ai);
                log("[LOG] Chosen activity ajustada: " + ai);
            }
        }
        List<ActivityInfo> chosenImportant = chosenActs.stream()
                .filter(a -> a.type.equals("home") || a.type.equals("work") || a.type.equals("school"))
                .collect(Collectors.toList());
        List<ActivityPair> chosenImportantPairs = new ArrayList<>();
        for (int i = 0; i < chosenImportant.size() - 1; i++) {
            chosenImportantPairs.add(new ActivityPair(chosenImportant.get(i), chosenImportant.get(i + 1)));
        }
        log("[LOG] Pares importantes (chosenPlan): " + chosenImportantPairs);

        // --- Para o primeiro par, gerar candidatos ---
        for (int i = 0; i < 1; i++) {
            ActivityPair pair = chosenImportantPairs.get(i);
            log("[LOG] Processando par (chosenPlan) " + pair);

            double originalDeparture = pair.from.endTime;
            if (originalDeparture < 0) {
                log("[WARN] Par " + pair + " sem horário de saída definido, pulando.");
                continue;
            }

            double minWindow = originalDeparture - omegaSeconds;
            double maxWindow = originalDeparture + omegaSeconds;
            log("[LOG] Janela de saída para " + pair.from.type + ": [" + minWindow + ", " + maxWindow + "]");

            // Busca horários reais de ônibus da mesma linha
            List<Double> busDepartures = getBusDeparturesForPair(pair, chosenPlan);
            log("[LOG] Par " + pair + " -> encontrados " + busDepartures.size() + " horários de ônibus.");

            for (double depTime : busDepartures) {
                if (depTime >= minWindow && depTime <= maxWindow) {
                    double accessWalk = 0.0;
                    for (PlanElement pe : chosenPlan.getPlanElements()) {
                        if (pe instanceof Leg && "walk".equals(((Leg) pe).getMode())) {
                            accessWalk = ((Leg) pe).getTravelTime().orElse(0.0);
                            break;
                        }
                    }
                    double homeDep = depTime - accessWalk - stmConfig.getBeta() * 60.0;
                    Plan newPlan = clonePlanWithNewDeparture(chosenPlan, pair.from, homeDep);
                    nearbyPlans.add(newPlan);
                    log("[LOG] Novo plano: ônibus=" + depTime + " walk=" + accessWalk
                            + " β=" + (stmConfig.getBeta() * 60.0) + " -> saída de casa=" + homeDep);
                } else {
                    log("[LOG] Horário de ônibus " + depTime + " fora da janela, descartado.");
                }
            }
        }

        // Inclui o chosenPlan original como candidato também
        nearbyPlans.add(chosenPlan);
        log("[LOG] Adicionando chosenPlan original como candidato para validação.");
        // --- Validação final com arrivalTargets ---
        log("[LOG] Iniciando validação final com arrivalTargets...");
        Set<Plan> validatedPlans = new LinkedHashSet<>();
        for (Plan candidate : nearbyPlans) {
            boolean valid = true;

            // Extrai activities relevantes do candidato
            List<ActivityInfo> candActs = new ArrayList<>();
            List<PlanElement> candElems = candidate.getPlanElements();
            for (int i = 0; i < candElems.size(); i++) {
                if (candElems.get(i) instanceof Activity) {
                    Activity act = (Activity) candElems.get(i);
                    double start = act.getStartTime().orElse(-1.0);
                    double end = act.getEndTime().orElse(-1.0);
                    if (start < 0 && i > 0 && candElems.get(i - 1) instanceof Leg) {
                        Leg prev = (Leg) candElems.get(i - 1);
                        double dep = prev.getDepartureTime().orElse(-1.0);
                        double dur = prev.getTravelTime().orElse(0.0);
                        if (dep >= 0 && dur >= 0) start = dep + dur;
                    }
                    if (end < 0 && i + 1 < candElems.size() && candElems.get(i + 1) instanceof Leg) {
                        Leg next = (Leg) candElems.get(i + 1);
                        double dep = next.getDepartureTime().orElse(-1.0);
                        if (dep >= 0) end = dep;
                    }
                    ActivityInfo ai = new ActivityInfo(i, act.getType(), start, end);
                    candActs.add(ai);
                    log("[LOG] Candidate activity ajustada: " + ai);
                }
            }
            List<ActivityInfo> candImportant = candActs.stream()
                    .filter(a -> a.type.equals("home") || a.type.equals("work") || a.type.equals("school"))
                    .collect(Collectors.toList());

            // Valida cada par
            for (int i = 0; i < candImportant.size() - 1; i++) {
                ActivityInfo from = candImportant.get(i);
                ActivityInfo to = candImportant.get(i + 1);

                // Caso: Saindo de 'home', chegada em 'work'
                if ("home".equals(from.type) && "work".equals(to.type)) {
                    double arrivalOrig = importantPairs.get(i).to.startTime >= 0 ?
                            importantPairs.get(i).to.startTime : importantPairs.get(i).to.endTime;
                    double arrivalCand = to.startTime >= 0 ? to.startTime : to.endTime;

                    // pega primeiro arrivalTarget após chegada original
                    double limit = -1.0;
                    for (double target : arrivalTargets) {
                        if (target > arrivalOrig) {
                            limit = target;
                            break;
                        }
                    }

                    log("[LOG] Validando chegada em work: arrivalOrig=" + arrivalOrig +
                            " arrivalCand=" + arrivalCand + " limit=" + limit);

                    if (limit > 0 && arrivalCand > limit) {
                        log("[LOG] Chegada em work ultrapassa limite (" + limit + ") -> descartando plano.");
                        valid = false;
                        break;
                    }
                }
            }

            if (valid) {
                validatedPlans.add(candidate);
                log("[LOG] Plano candidato mantido.");
            } else {
                log("[LOG] Plano candidato descartado.");
            }
        }

        log("[LOG] filterNearbyPlans finalizado. Planos válidos=" + validatedPlans.size());
        // Log detalhado dos planos validados
        int planCounter = 1;
        for (Plan vp : validatedPlans) {
            log("[LOG] --- Plano validado #" + planCounter + " ---");
            List<PlanElement> elems = vp.getPlanElements();
            for (int i = 0; i < elems.size(); i++) {
                PlanElement pe = elems.get(i);
                if (pe instanceof Activity) {
                    Activity act = (Activity) pe;
                    log("[LOG] Activity index=" + i +
                            " type=" + act.getType() +
                            " start=" + (act.getStartTime().isDefined() ? act.getStartTime().seconds() : -1) +
                            " end=" + (act.getEndTime().isDefined() ? act.getEndTime().seconds() : -1));
                } else if (pe instanceof Leg) {
                    Leg leg = (Leg) pe;
                    log("[LOG] Leg index=" + i +
                            " mode=" + leg.getMode() +
                            " routingMode=" + leg.getRoutingMode() +
                            " depTime=" + (leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds() : -1) +
                            " travelTime=" + (leg.getTravelTime().isDefined() ? leg.getTravelTime().seconds() : -1));
                }
            }
            planCounter++;
        }
        //return null;
        return validatedPlans;
    }

    private Set<Plan> filterNearbyPlans(
            Plan chosenPlan,
            Plan originalPlan,
            List<Double> arrivalTargets,
            double omegaMinutes
    ) {
        log("[LOG] Iniciando filterNearbyPlans...");
        double omegaSeconds = omegaMinutes * 60.0;
        log("[LOG] Tolerância temporal definida: omegaMinutes=" + omegaMinutes + " -> omegaSeconds=" + omegaSeconds);

        Set<Plan> nearbyPlans = new LinkedHashSet<>();

        // --- Extrai activities relevantes do plano original ---
        log("[LOG] Extraindo activities do originalPlan...");
        List<ActivityInfo> origActs = new ArrayList<>();
        List<PlanElement> origElems = originalPlan.getPlanElements();
        for (int i = 0; i < origElems.size(); i++) {
            if (origElems.get(i) instanceof Activity) {
                Activity act = (Activity) origElems.get(i);
                double start = act.getStartTime().orElse(-1.0);
                double end = act.getEndTime().orElse(-1.0);
                if (start < 0 && i > 0 && origElems.get(i - 1) instanceof Leg) {
                    Leg prev = (Leg) origElems.get(i - 1);
                    double dep = prev.getDepartureTime().orElse(-1.0);
                    double dur = prev.getTravelTime().orElse(0.0);
                    if (dep >= 0 && dur >= 0) start = dep + dur;
                }
                if (end < 0 && i + 1 < origElems.size() && origElems.get(i + 1) instanceof Leg) {
                    Leg next = (Leg) origElems.get(i + 1);
                    double dep = next.getDepartureTime().orElse(-1.0);
                    if (dep >= 0) end = dep;
                }
                ActivityInfo ai = new ActivityInfo(i, act.getType(), start, end);
                origActs.add(ai);
                log("[LOG] Original activity ajustada: " + ai);
            }
        }
        List<ActivityInfo> importantActs = origActs.stream()
                .filter(a -> a.type.equals("home") || a.type.equals("work") || a.type.equals("school"))
                .collect(Collectors.toList());
        List<ActivityPair> importantPairs = new ArrayList<>();
        for (int i = 0; i < importantActs.size() - 1; i++) {
            importantPairs.add(new ActivityPair(importantActs.get(i), importantActs.get(i + 1)));
        }
        log("[LOG] Pares importantes (original): " + importantPairs);
        // --- Extrai activities relevantes do chosenPlan ---
        List<ActivityInfo> chosenActs = new ArrayList<>();
        List<PlanElement> chosenElems = chosenPlan.getPlanElements();
        for (int i = 0; i < chosenElems.size(); i++) {
            if (chosenElems.get(i) instanceof Activity) {
                Activity act = (Activity) chosenElems.get(i);
                double start = act.getStartTime().orElse(-1.0);
                double end = act.getEndTime().orElse(-1.0);

                if (start < 0 && i > 0 && chosenElems.get(i - 1) instanceof Leg) {
                    Leg prev = (Leg) chosenElems.get(i - 1);
                    double dep = prev.getDepartureTime().orElse(-1.0);
                    double dur = prev.getTravelTime().orElse(0.0);
                    if (dep >= 0 && dur >= 0) start = dep + dur;
                }
                if (end < 0 && i + 1 < chosenElems.size() && chosenElems.get(i + 1) instanceof Leg) {
                    Leg next = (Leg) chosenElems.get(i + 1);
                    double dep = next.getDepartureTime().orElse(-1.0);
                    if (dep >= 0) end = dep;
                }

                ActivityInfo ai = new ActivityInfo(i, act.getType(), start, end);
                chosenActs.add(ai);
                log("[LOG] Chosen activity ajustada: " + ai);
            }
        }
        List<ActivityInfo> chosenImportant = chosenActs.stream()
                .filter(a -> a.type.equals("home") || a.type.equals("work") || a.type.equals("school"))
                .collect(Collectors.toList());
        List<ActivityPair> chosenImportantPairs = new ArrayList<>();
        for (int i = 0; i < chosenImportant.size() - 1; i++) {
            chosenImportantPairs.add(new ActivityPair(chosenImportant.get(i), chosenImportant.get(i + 1)));
        }
        log("[LOG] Pares importantes (chosenPlan): " + chosenImportantPairs);

        // --- Para cada par, gerar candidatos ---
        for (int i = 0; i < chosenImportantPairs.size(); i++) {
            ActivityPair pair = chosenImportantPairs.get(i);
            log("[LOG] Processando par (chosenPlan) " + pair);

            double originalDeparture = pair.from.endTime;
            if (originalDeparture < 0) {
                log("[WARN] Par " + pair + " sem horário de saída definido, pulando.");
                continue;
            }

            double minWindow = originalDeparture - omegaSeconds;
            double maxWindow = originalDeparture + omegaSeconds;
            log("[LOG] Janela de saída para " + pair.from.type + ": [" + minWindow + ", " + maxWindow + "]");

            // Busca horários reais de ônibus da mesma linha
            List<Double> busDepartures = getBusDeparturesForPair(pair, chosenPlan);
            log("[LOG] Par " + pair + " -> encontrados " + busDepartures.size() + " horários de ônibus.");

            for (double depTime : busDepartures) {
                if (depTime >= minWindow && depTime <= maxWindow) {
                    Plan newPlan = clonePlanWithNewDeparture(chosenPlan, pair.from, depTime);
                    nearbyPlans.add(newPlan);
                    log("[LOG] Novo plano gerado para par " + pair + " com saída=" + depTime);
                } else {
                    log("[LOG] Horário de ônibus " + depTime + " fora da janela, descartado.");
                }
            }
        }


        // Inclui o chosenPlan original como candidato também
        nearbyPlans.add(chosenPlan);
        log("[LOG] Adicionando chosenPlan original como candidato para validação.");
        // --- Validação final com arrivalTargets ---
        log("[LOG] Iniciando validação final com arrivalTargets...");
        Set<Plan> validatedPlans = new LinkedHashSet<>();
        for (Plan candidate : nearbyPlans) {
            boolean valid = true;

            // Extrai activities relevantes do candidato
            List<ActivityInfo> candActs = new ArrayList<>();
            List<PlanElement> candElems = candidate.getPlanElements();
            for (int i = 0; i < candElems.size(); i++) {
                if (candElems.get(i) instanceof Activity) {
                    Activity act = (Activity) candElems.get(i);
                    double start = act.getStartTime().orElse(-1.0);
                    double end = act.getEndTime().orElse(-1.0);
                    if (start < 0 && i > 0 && candElems.get(i - 1) instanceof Leg) {
                        Leg prev = (Leg) candElems.get(i - 1);
                        double dep = prev.getDepartureTime().orElse(-1.0);
                        double dur = prev.getTravelTime().orElse(0.0);
                        if (dep >= 0 && dur >= 0) start = dep + dur;
                    }
                    if (end < 0 && i + 1 < candElems.size() && candElems.get(i + 1) instanceof Leg) {
                        Leg next = (Leg) candElems.get(i + 1);
                        double dep = next.getDepartureTime().orElse(-1.0);
                        if (dep >= 0) end = dep;
                    }
                    ActivityInfo ai = new ActivityInfo(i, act.getType(), start, end);
                    candActs.add(ai);
                    log("[LOG] Candidate activity ajustada: " + ai);
                }
            }
            List<ActivityInfo> candImportant = candActs.stream()
                    .filter(a -> a.type.equals("home") || a.type.equals("work") || a.type.equals("school"))
                    .collect(Collectors.toList());

            // Valida cada par
            for (int i = 0; i < candImportant.size() - 1; i++) {
                ActivityInfo from = candImportant.get(i);
                ActivityInfo to = candImportant.get(i + 1);

                // Caso: chegada em 'work'
                if ("work".equals(to.type)) {
                    double arrivalOrig = importantPairs.get(i).to.startTime >= 0 ?
                            importantPairs.get(i).to.startTime : importantPairs.get(i).to.endTime;
                    double arrivalCand = to.startTime >= 0 ? to.startTime : to.endTime;

                    // pega primeiro arrivalTarget após chegada original
                    double limit = -1.0;
                    for (double target : arrivalTargets) {
                        if (target > arrivalOrig) {
                            limit = target;
                            break;
                        }
                    }

                    log("[LOG] Validando chegada em work: arrivalOrig=" + arrivalOrig +
                            " arrivalCand=" + arrivalCand + " limit=" + limit);

                    if (limit > 0 && arrivalCand > limit) {
                        log("[LOG] Chegada em work ultrapassa limite (" + limit + ") -> descartando plano.");
                        valid = false;
                        break;
                    }
                }

                // Caso: saída de 'work'
                if ("work".equals(from.type)) {
                    double departureOrig = importantPairs.get(i).from.endTime;
                    double departureCand = from.endTime;

                    // pega primeiro arrivalTarget antes da saída original
                    double limit = -1.0;
                    for (double target : arrivalTargets) {
                        if (target < departureOrig) {
                            limit = target;
                            break;
                        }
                    }

                    log("[LOG] Validando saída de work: departureOrig=" + departureOrig +
                            " departureCand=" + departureCand + " limit=" + limit);

                    if (limit > 0 && departureCand < limit) {
                        log("[LOG] Saída de work anterior ao limite (" + limit + ") -> descartando plano.");
                        valid = false;
                        break;
                    }
                }
            }

            if (valid) {
                validatedPlans.add(candidate);
                log("[LOG] Plano candidato mantido.");
            } else {
                log("[LOG] Plano candidato descartado.");
            }
        }

        log("[LOG] filterNearbyPlans finalizado. Planos válidos=" + validatedPlans.size());
        // Log detalhado dos planos validados
        int planCounter = 1;
        for (Plan vp : validatedPlans) {
            log("[LOG] --- Plano validado #" + planCounter + " ---");
            List<PlanElement> elems = vp.getPlanElements();
            for (int i = 0; i < elems.size(); i++) {
                PlanElement pe = elems.get(i);
                if (pe instanceof Activity) {
                    Activity act = (Activity) pe;
                    log("[LOG] Activity index=" + i +
                            " type=" + act.getType() +
                            " start=" + (act.getStartTime().isDefined() ? act.getStartTime().seconds() : -1) +
                            " end=" + (act.getEndTime().isDefined() ? act.getEndTime().seconds() : -1));
                } else if (pe instanceof Leg) {
                    Leg leg = (Leg) pe;
                    log("[LOG] Leg index=" + i +
                            " mode=" + leg.getMode() +
                            " routingMode=" + leg.getRoutingMode() +
                            " depTime=" + (leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds() : -1) +
                            " travelTime=" + (leg.getTravelTime().isDefined() ? leg.getTravelTime().seconds() : -1));
                }
            }
            planCounter++;
        }
        //return null;
        return validatedPlans;
    }


    private List<Double> getBusDeparturesForPair(ActivityPair pair, Plan chosenPlan) {
        log("[LOG] Buscando horários de ônibus (LINHA, na parada de embarque) para par: " + pair);
        List<Double> departures = new ArrayList<>();
        List<PlanElement> elements = chosenPlan.getPlanElements();

        for (int i = pair.from.index + 1; i < pair.to.index; i++) {
            if (!(elements.get(i) instanceof Leg)) continue;
            Leg leg = (Leg) elements.get(i);
            if (!"pt".equals(leg.getMode()) || !(leg.getRoute() instanceof TransitPassengerRoute)) continue;

            TransitPassengerRoute pr = (TransitPassengerRoute) leg.getRoute();
            Id<TransitStopFacility> boardStop = pr.getAccessStopId();   // parada de embarque do aluno
            TransitLine line = scenario.getTransitSchedule().getTransitLines().get(pr.getLineId());
            if (line == null) {
                log("[WARN] linha " + pr.getLineId() + " não encontrada");
                continue;
            }

            // JOÃO: percorre TODAS as rotas da LINHA (cada rota = 1 trip), não só a rota original
            for (TransitRoute route : line.getRoutes().values()) {

                // acha a parada de embarque nesta rota e seu offset de saída
                boolean serves = false;
                double boardOffset = 0.0;
                for (TransitRouteStop stop : route.getStops()) {
                    if (boardStop.equals(stop.getStopFacility().getId())) {
                        boardOffset = stop.getDepartureOffset().isDefined() ? stop.getDepartureOffset().seconds()
                                : (stop.getArrivalOffset().isDefined() ? stop.getArrivalOffset().seconds() : 0.0);
                        serves = true;
                        break;
                    }
                }
                if (!serves) continue;   // esta rota da linha NÃO passa na parada do aluno -> ignora

                // horário em que o ônibus PASSA NA PARADA DO ALUNO = saída da rota + offset da parada
                for (Departure dep : route.getDepartures().values()) {
                    departures.add(dep.getDepartureTime() + boardOffset);
                }
            }
        }
        departures.sort(Double::compareTo);
        log("[LOG] Total de horários coletados (linha, na parada) para " + pair + ": " + departures.size());
        return departures;
    }

    /**
     * Propaga um relógio a partir da saída de casa: cada leg PARTE quando a activity
     * anterior termina, e o relógio avança pelo travelTime do leg. Assim o fim de um
     * leg e o início da activity/leg seguinte SEMPRE batem — nunca "já está atrasado".
     */
    private void setLegDepartureTimes(Plan plan, double homeDeparture) {
        List<PlanElement> els = plan.getPlanElements();
        double clock = homeDeparture;
        for (PlanElement pe : els) {
            if (pe instanceof Activity) {
                Activity a = (Activity) pe;
                // se a activity tem saída definida (a saída de casa), o relógio salta pra ela
                if (a.getEndTime().isDefined() && a.getEndTime().seconds() > clock) {
                    clock = a.getEndTime().seconds();
                }
            } else if (pe instanceof Leg) {
                Leg l = (Leg) pe;
                l.setDepartureTime(clock);                     // parte quando a etapa anterior terminou
                clock += l.getTravelTime().orElse(0.0);        // chega depois do travelTime
            }
        }
    }

    private Plan clonePlanWithNewDeparture(Plan chosenPlan, ActivityInfo fromActivity, double newDeparture) {
        log("[LOG] Clonando plano; nova saída de casa=" + newDeparture + " (activity index=" + fromActivity.index + ")");
        PopulationFactory factory = scenario.getPopulation().getFactory();
        Plan newPlan = factory.createPlan();
        List<PlanElement> elements = chosenPlan.getPlanElements();

        for (int i = 0; i < elements.size(); i++) {
            PlanElement pe = elements.get(i);
            if (pe instanceof Activity) {
                Activity o = (Activity) pe;
                Activity a = factory.createActivityFromLinkId(o.getType(), o.getLinkId());
                a.setCoord(o.getCoord());
                // NÃO copia start/end antigos (ficavam stale); só define a saída de casa
                if (i == fromActivity.index) a.setEndTime(newDeparture);
                newPlan.addActivity(a);
            } else if (pe instanceof Leg) {
                Leg o = (Leg) pe;
                Leg l = factory.createLeg(o.getMode());
                l.setRoute(o.getRoute());                                   // mantém a rota
                if (o.getRoutingMode() != null) l.setRoutingMode(o.getRoutingMode());
                if (o.getTravelTime().isDefined())
                    l.setTravelTime(o.getTravelTime().seconds());  // travelTime é preciso p/ propagar
                // NÃO copia o departureTime antigo — será recalculado
                newPlan.addLeg(l);
            }
        }
        setLegDepartureTimes(newPlan, newDeparture);   // JOÃO: consistência centralizada aqui
        return newPlan;
    }

    private Plan chooseLatestFeasiblePlan(Set<Plan> feasiblePlans) {
        if (feasiblePlans == null || feasiblePlans.isEmpty()) {
            log("[LOG] Nenhum plano viável encontrado no passo 5.");
            return null;
        }

        // ordena pelo horário de chegada
        return feasiblePlans.stream()
                .max(Comparator.comparingDouble(p -> {
                    double arrival = -1;
                    for (PlanElement pe : p.getPlanElements()) {
                        if (pe instanceof Leg) {
                            Leg leg = (Leg) pe;
                            double dep = leg.getDepartureTime().orElse(-1.0);
                            double dur = leg.getTravelTime().orElse(0.0);
                            arrival = dep + dur;
                        }
                    }
                    return arrival;
                }))
                .orElse(null);
    }

    private void logFinalDepartureAndArrival(Plan finalPlan, double betaMinutes) {
        double departureTime = -1;
        double travelTime = 0;

        // pega o primeiro leg para calcular saída
        for (PlanElement pe : finalPlan.getPlanElements()) {
            if (pe instanceof Leg) {
                Leg leg = (Leg) pe;
                departureTime = leg.getDepartureTime().orElse(-1.0);
                travelTime = leg.getTravelTime().orElse(0.0);
                break; // só o primeiro leg
            }
        }

        if (departureTime <= 0) {
            log("[LOG] Plano final não possui departureTime válido.");
            return;
        }

        // aplica beta (minutos → segundos)
        double betaSeconds = betaMinutes * 60.0;
        double newDeparture = departureTime - betaSeconds;

        // calcula chegada (último leg)
        double arrivalTime = -1;
        for (PlanElement pe : finalPlan.getPlanElements()) {
            if (pe instanceof Leg) {
                Leg leg = (Leg) pe;
                double dep = leg.getDepartureTime().orElse(-1.0);
                double dur = leg.getTravelTime().orElse(0.0);
                arrivalTime = dep + dur;
            }
        }

        // formata HH:mm
        String departureFormatted = String.format("%02d:%02d",
                (int) (newDeparture / 3600), (int) ((newDeparture % 3600) / 60));
        String arrivalFormatted = String.format("%02d:%02d",
                (int) (arrivalTime / 3600), (int) ((arrivalTime % 3600) / 60));

        log("[LOG] Novo horário de saída de casa=" + departureFormatted +
                " | Horário de chegada previsto=" + arrivalFormatted);
    }

    private TransitStopFacility selectNearbyStopStochastically(Map<TransitStopFacility, Double> nearbyStops) {
        if (nearbyStops == null || nearbyStops.isEmpty()) {
            log("[LOG] Nenhuma parada encontrada para seleção estocástica.");
            return null;
        }

        // soma total dos scores
        double total = nearbyStops.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            log("[LOG] Scores somaram <= 0. Retornando primeira parada.");
            return nearbyStops.keySet().iterator().next();
        }

        // sorteia um número entre 0 e total
        double rand = org.matsim.core.gbl.MatsimRandom.getRandom().nextDouble() * total;
        log("[LOG] Número aleatório sorteado = " + rand);

        double cumulative = 0.0;
        for (Map.Entry<TransitStopFacility, Double> entry : nearbyStops.entrySet()) {
            cumulative += entry.getValue();
            log("[LOG] Stop " + entry.getKey().getId() + " score=" + entry.getValue() +
                    " cumulativo=" + cumulative);
            if (rand <= cumulative) {
                log("[LOG] Selecionado stop " + entry.getKey().getId() +
                        " com score=" + entry.getValue());
                return entry.getKey();
            }
        }

        // fallback: último stop
        log("[LOG] Nenhum stop selecionado no loop. Retornando último stop.");
        return nearbyStops.keySet().iterator().next();
    }

    private ChosenStopInfo selectStopForElement(AlternativeTripGenerator.PlanElementInfo info) {
        TransitStopFacility chosenStop = selectNearbyStopStochastically(info.getNearbyStops());
        return new ChosenStopInfo(
                info.getIndex(),
                info.getType(),
                info.getLinkId(),
                info.getStartTime(),
                info.getEndTime(),
                chosenStop
        );
    }

    public static class ChosenStopInfo {
        private final int index;
        private final String type;
        private final Id<Link> linkId;
        private final TransitStopFacility chosenStop;
        private final double startTime;
        private final double endTime;


        public ChosenStopInfo(int index, String type, Id<Link> linkId,
                              double startTime, double endTime, TransitStopFacility chosenStop) {
            this.index = index;
            this.type = type;
            this.linkId = linkId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.chosenStop = chosenStop;
        }

        public int getIndex() {
            return index;
        }

        public String getType() {
            return type;
        }

        public Id<Link> getLinkId() {
            return linkId;
        }

        public double getStartTime() {
            return startTime;
        }

        public double getEndTime() {
            return endTime;
        }

        public TransitStopFacility getChosenStop() {
            return chosenStop;
        }
        // ✅ Novo método para verificar null ou vazio
        public boolean isChosenStopNullOrEmpty() {
            return chosenStop == null || chosenStop.getId() == null || chosenStop.getId().toString().isEmpty();
        }

        @Override
        public String toString() {
            return "ChosenStopInfo[index=" + index +
                    ", type=" + type +
                    ", LinkId=" + linkId +
                    ", start=" + startTime +
                    ", end=" + endTime +
                    ", chosenStop=" + (chosenStop != null ? chosenStop : "null") + "]";
        }
    }

    private ChosenStopInfo cloneWithBoardingWindow(ChosenStopInfo original, double omegaSeconds) {


        return new ChosenStopInfo(
                original.getIndex(),
                original.getType(),
                original.getLinkId(),
                omegaSeconds, // novo startTime representando janela de embarque
                original.getEndTime(),
                original.getChosenStop()
        );
    }

    public static class TripSegment {
        private final int originIndex;
        private final int destIndex;
        private final Activity originActivity;
        private final Activity destActivity;
        private final List<? extends PlanElement> trip;

        public TripSegment(int originIndex, int destIndex,
                           Activity originActivity,
                           Activity destActivity,
                           List<? extends PlanElement> trip) {
            this.originIndex = originIndex;
            this.destIndex = destIndex;
            this.originActivity = originActivity;
            this.destActivity = destActivity;
            this.trip = trip;
        }

        public int getOriginIndex() {
            return originIndex;
        }

        public int getDestIndex() {
            return destIndex;
        }

        public Activity getOriginActivity() {
            return originActivity;
        }

        public Activity getDestActivity() {
            return destActivity;
        }

        public List<? extends PlanElement> getTrip() {
            return trip;
        }
    }

    private boolean isSegmentValid(Activity origin,
                                   List<? extends PlanElement> trip,
                                   Activity dest,
                                   List<Double> arrivalTargets) {
        // fallback inicial: se não houver endTime definido, começa em 0
        double currentTime = origin.getEndTime() != null ? origin.getEndTime().seconds() : 0.0;
        StringBuilder timeline = new StringBuilder();
        timeline.append("[").append(origin.getType())
                .append(" end=").append(currentTime);

        // Caso especial: chegada em "work"
        if ("work".equals(dest.getType())) {
            Double lastEndTime = null;
            for (int k = trip.size() - 1; k >= 0; k--) {
                if (trip.get(k) instanceof Leg) {
                    Leg leg = (Leg) trip.get(k);
                    double dep = leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds() : currentTime;
                    double tt = leg.getTravelTime().isDefined() ? leg.getTravelTime().seconds() : 0.0;
                    lastEndTime = dep + tt;
                    break;
                }
            }
            if (lastEndTime != null) {
                double arrivalOrig = dest.getEndTime().isDefined() ? dest.getEndTime().seconds() : currentTime;
                Double finalLastEndTime = lastEndTime;
                double limit = arrivalTargets.stream()
                        .filter(t -> t > finalLastEndTime)
                        .findFirst()
                        .orElse(-1.0);

                log("[VALIDAÇÃO] Chegada em work: arrivalOrig=" + arrivalOrig +
                        " lastEndTime=" + lastEndTime + " limit=" + limit);

                if (limit > 0 && lastEndTime > limit) {
                    log("[CONSISTENCY] Falha: chegada em work ultrapassa limite.");
                    return false;
                }
            }
        }

        // Caso especial: saída de "work"
        if ("work".equals(origin.getType())) {
            double finalCurrentTime = currentTime;
            Double firstStartTime = trip.stream()
                    .filter(pe -> pe instanceof Leg)
                    .map(pe -> {
                        Leg leg = (Leg) pe;
                        return leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds() : finalCurrentTime;
                    })
                    .findFirst()
                    .orElse(null);

            double departureOrig = origin.getStartTime().isDefined() ? origin.getStartTime().seconds() : currentTime;
            double limit = arrivalTargets.stream()
                    .filter(t -> t < departureOrig)
                    .findFirst()
                    .orElse(-1.0);

            log("[VALIDAÇÃO] Saída de work: departureOrig=" + departureOrig +
                    " departureCand=" + firstStartTime + " limit=" + limit);

            if (limit > 0 && firstStartTime != null && firstStartTime < limit) {
                log("[CONSISTENCY] Falha: saída de work anterior ao limite.");
                return false;
            }
        }
        double dep = origin.getEndTime().isDefined() ? origin.getEndTime().seconds() : currentTime;
        double tt = 0.0;
        double arr = dep + tt;
        // Consistência geral do trip
        for (PlanElement pe : trip) {

            if (pe instanceof Leg) {
                Leg leg = (Leg) pe;
                dep = leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds() : currentTime;
                tt = leg.getTravelTime().isDefined() ? leg.getTravelTime().seconds() : 0.0;
                arr = dep + tt;
                System.out.println(leg);
                System.out.println("[SEGMENT LEG] Parâmetros Start=" + dep + " End=" + arr + " CurrentTime=" + currentTime);
                if (dep < currentTime) {
                    log("[CONSISTENCY] Falha: leg parte antes do tempo acumulado.");
                    return false;
                }
                currentTime = arr;
                timeline.append(" -> Leg end=").append(currentTime);
            } else if (pe instanceof Activity) {
                Activity act = (Activity) pe;
                System.out.println(act);
                System.out.println("[SEGMENT ACTIVITY] Parâmetros ]");
                double start = act.getStartTime().isDefined() ? act.getStartTime().seconds() : arr;
                double end = act.getEndTime().isDefined() ? act.getEndTime().seconds() : start;
                System.out.println(act);
                System.out.println("[SEGMENT ACTIVITY] Parâmetros: Start=" + start + " End=" + end + " CurrentTime=" + currentTime);
                if (start < currentTime) {
                    log("[CONSISTENCY] Falha: activity começa antes do tempo acumulado.");
                    return false;
                }
                currentTime = end;
                timeline.append(" -> ").append(act.getType())
                        .append(" end=").append(currentTime);
            }
        }

        // Verificação final: destino não pode começar antes do tempo acumulado
        double destStart = dest.getStartTime().isDefined() ? dest.getStartTime().seconds() : currentTime;

        System.out.println("[SEGMENT ACTIVITY] Parâmetros: Start=" + destStart + " CurrentTime=" + currentTime);
        /*
        if (destStart < currentTime) {
            log("[CONSISTENCY] Falha: destino começa antes da chegada acumulada.");
            return false;
        }

         */

        timeline.append(" -> ").append(dest.getType())
                .append(" start=").append(destStart);
        log("[TIMELINE] " + timeline.toString() + "]");

        return true;
    }


    private List<Plan> generateBoardingWindowPlans(Person person,
                                                   List<ChosenStopInfo> boardingWindows,
                                                   TripRouter tripRouter, List<Double> arrivalTargets
    ) {
        List<Plan> candidates = new ArrayList<>();
        List<TripSegment> segments = new ArrayList<>();
        int startIndex = 0;
        int endIndex = 0;
        int actualIndex = 0;
        String infoType = null;
        List<int[]> pares = new ArrayList<>();
        for (int i = 0; i < boardingWindows.size(); i++) {
            ChosenStopInfo info = boardingWindows.get(i);
            System.out.println("[LOG] Type:" + info.getType());

            if (actualIndex != info.getIndex()) {
                endIndex = i - 1;
                if (infoType.contains("act") && !infoType.contains("pt")) {
                    pares.add(new int[]{startIndex, endIndex, actualIndex});
                }
                actualIndex = info.getIndex();
                startIndex = i;
            }
            infoType = info.getType();
        }
        endIndex = boardingWindows.size() - 1;
        if (infoType.contains("act") && !infoType.contains("pt")) {
            pares.add(new int[]{startIndex, endIndex, actualIndex});
        }

        // LOG de todos os pares
        System.out.println("[LOG] Pares encontrados:");
        for (int[] p : pares) {
            System.out.println("  startIndex=" + p[0] +
                    " endIndex=" + p[1] +
                    " actualIndex=" + p[2]);
        }

        if (pares.size()==1){return candidates;}


        if(pares.size()>=3){
            // LOG de todos os pares
            System.out.println("[LOG] Viagem com mais de uma sequência:");
        }
        for (int idx = 0; idx < pares.size() - 1; idx++) {
            int[] parAtual = pares.get(idx);
            int[] proximoPar = pares.get(idx + 1);

            for (int i = parAtual[0]; i <= parAtual[1]; i++) {
                ChosenStopInfo startInfo = boardingWindows.get(i);

                System.out.println("Index" + i + ". Start Info: " + startInfo);

                // ignora pt-interaction
                if ("pt interaction".equals(startInfo.getType()) || startInfo.getType().contains("leg")) continue;

                // procura próxima activity importante
                for (int j = proximoPar[0]; j <= proximoPar[1]; j++) {
                    ChosenStopInfo endInfo = boardingWindows.get(j);
                    System.out.println("Index" + j + ". End Info: " + endInfo);
                    if ("pt interaction".equals(endInfo.getType()) || endInfo.getType().contains("leg")) continue;

                    // determina departureTime para o segmento:
                    double departureForSegment = startInfo.getEndTime();

                    // adiciona activity inicial
                    Activity origin = PopulationUtils.createActivityFromLinkId(
                            startInfo.getType(), startInfo.getLinkId());
                    origin.setStartTime(startInfo.getStartTime());
                    origin.setEndTime(startInfo.getEndTime());
                    //Facility origFacility = (Facility) origin.getFacilityId();


                    // adiciona activity final
                    Activity dest = PopulationUtils.createActivityFromLinkId(
                            endInfo.getType(), endInfo.getLinkId());
                    dest.setStartTime(endInfo.getStartTime());
                    dest.setEndTime(endInfo.getEndTime());
                    //Facility destFacility = (Facility) dest.getFacilityId();
                    //log(origFacility.toString());
                    //log(departureForSegment + " - " + destFacility.toString());

                    // cria facilities para calcRoute
                    Facility originFac = startInfo.getChosenStop();
                    Facility destFac = endInfo.getChosenStop();
                    if (startInfo.isChosenStopNullOrEmpty() || endInfo.isChosenStopNullOrEmpty()) {return candidates;}

                    System.out.println("Facility Origem: " + originFac);
                    System.out.println("Horário de saída: " + departureForSegment);
                    System.out.println("Facility Destino: " + destFac);

                    // calcula rota entre boardingWindow → próxima activity importante
                    List<? extends PlanElement> trip = tripRouter.calcRoute(
                            TransportMode.pt,
                            originFac,
                            destFac,
                            departureForSegment,
                            person,
                            person.getAttributes()
                    );
                    //Removendo checagem de segmento válido
                    //if (isSegmentValid(origin, trip, dest, arrivalTargets)) {
                        segments.add(new TripSegment(i, j, origin, dest, trip));
                    //    log("[SEGMENT] Válido: " + i + " -> " + j);
                    // } else {
                    //    log("[SEGMENT] Inválido: " + i + " -> " + j);
                    //}
                    //break; // para no primeiro activity importante encontrado
                }


            }
        }
        System.out.println("Segmentos de viagem registrados: " + segments.size());

        List<Plan> planos = new ArrayList<>();

// chamada inicial
        int[] parAtual = pares.get(0);
        int[] proximoPar = pares.get(1);

        for (int i = parAtual[0]; i <= parAtual[1]; i++) {
            for (int j = proximoPar[0]; j <= proximoPar[1]; j++) {
                TripSegment seg = encontrarSegmento(segments, i, j);
                if (seg == null) continue;

                // plano inicial
                Plan planoInicial = PopulationUtils.createPlan(person);
                // normaliza o tipo da origem
                Activity origin = seg.getOriginActivity();
                origin.setType(origin.getType().replace("activity-", ""));
                planoInicial.addActivity(origin);
                for (PlanElement pe : seg.getTrip()) {
                    if (pe instanceof Activity) {
                        Activity act = (Activity) pe;
                        act.setType(act.getType().replace("activity-", ""));
                        planoInicial.addActivity(act);
                    } else if (pe instanceof Leg) {
                        planoInicial.addLeg((Leg) pe);
                    }
                }
                // normaliza o tipo do destino
                Activity dest = seg.getDestActivity();
                dest.setType(dest.getType().replace("activity-", ""));
                planoInicial.addActivity(dest);

                // chama recursão
                generatePlansRecursively(planoInicial, j, 1, pares, segments, planos, person);

            }
        }

        return planos;



        /*
        // 2. Monta planos concatenando segmentos
        List<Plan> plans = new ArrayList<>();
        for (TripSegment seg : segments) {
            List<Activity> activities = new ArrayList<>();
            List<PlanElement> trips = new ArrayList<>();

            activities.add(seg.getOriginActivity());
            trips.addAll(seg.getTrip());
            activities.add(seg.getDestActivity());

            // DFS para expandir sequências
            expandPlans(person, seg, segments, activities, trips, plans);
        }

        return plans;

         */
        //return null;
    }

    private TripSegment encontrarSegmento(List<TripSegment> segments, int i, int j) {
        for (TripSegment seg : segments) {
            if (seg.getOriginIndex() == i && seg.getDestIndex() == j) {
                return seg;
            }
        }
        return null;
    }

    private void generatePlansRecursively(Plan plano,
                                          int currentIndex,
                                          int parPos,
                                          List<int[]> pares,
                                          List<TripSegment> segments,
                                          List<Plan> planos,
                                          Person person) {
        // condição de parada
        if (parPos >= pares.size() - 1) {
            planos.add(plano);
            System.out.println("[PLAN FINAL] " + plano.getPlanElements().stream()
                    .map(pe -> (pe instanceof Activity) ? ((Activity) pe).getType() : "leg")
                    .collect(Collectors.joining(" -> ")));
            return;
        }

        int[] proximoPar = pares.get(parPos + 1);

        for (int j = proximoPar[0]; j <= proximoPar[1]; j++) {
            TripSegment seg = encontrarSegmento(segments, currentIndex, j);
            if (seg == null) continue;

            // cria uma cópia do plano atual
            Plan novoPlano = PopulationUtils.createPlan(person);
            for (PlanElement pe : plano.getPlanElements()) {
                if (pe instanceof Activity) {
                    Activity act = (Activity) pe;
                    act.setType(act.getType().replace("activity-", ""));
                    novoPlano.addActivity(act);
                } else if (pe instanceof Leg) {
                    novoPlano.addLeg((Leg) pe);
                }
            }

            // concatena próximo segmento
            for (PlanElement pe : seg.getTrip()) {
                if (pe instanceof Activity) {
                    novoPlano.addActivity((Activity) pe);
                } else if (pe instanceof Leg) {
                    novoPlano.addLeg((Leg) pe);
                }
            }
            // normaliza o tipo do destino
            Activity dest = seg.getDestActivity();
            dest.setType(dest.getType().replace("activity-", ""));
            novoPlano.addActivity(dest);


            // recursão
            generatePlansRecursively(novoPlano, j, parPos + 1, pares, segments, planos, person);
        }
    }



    private void expandPlans(Person person,
                             TripSegment currentSeg,
                             List<TripSegment> allSegments,
                             List<Activity> activities,
                             List<PlanElement> trips,
                             List<Plan> plans) {
        // encontra segmentos que continuam a partir do destino atual
        List<TripSegment> nextSegments = allSegments.stream()
                .filter(s -> s.getOriginIndex() == currentSeg.getDestIndex())
                .collect(Collectors.toList());

        if (nextSegments.isEmpty()) {
            // chegou ao fim -> monta plano
            Plan plan = PopulationUtils.createPlan(person);
            for (int i = 0; i < activities.size(); i++) {
                plan.addActivity(activities.get(i));
                if (i < trips.size()) {
                    plan.getPlanElements().add(trips.get(i));
                }
            }
            plans.add(plan);

            // LOG da sequência
            String seq = activities.stream()
                    .map(a -> a.getLinkId().toString())
                    .collect(Collectors.joining(" -> "));
            System.out.println("[FINAL PLAN] " + seq);
            return;
        }

        // continua expandindo
        for (TripSegment nextSeg : nextSegments) {
            List<Activity> newActivities = new ArrayList<>(activities);
            List<PlanElement> newTrips = new ArrayList<>(trips);

            newTrips.addAll(nextSeg.getTrip());
            newActivities.add(nextSeg.getDestActivity());

            expandPlans(person, nextSeg, allSegments, newActivities, newTrips, plans);
        }
    }
    // DFS recursivo
    private void buildPlansDFS(Person person,
                               TripSegment currentSeg,
                               Map<Integer, List<TripSegment>> graph,
                               List<Activity> activities,
                               List<PlanElement> trips,
                               List<Plan> plans) {
        // adiciona origem se for o primeiro segmento
        if (activities.isEmpty()) {
            activities.add(currentSeg.getOriginActivity());
        }

        // adiciona trip e destino
        trips.addAll(currentSeg.getTrip());
        activities.add(currentSeg.getDestActivity());

        // se não há mais segmentos a partir do destino atual -> plano completo
        if (!graph.containsKey(currentSeg.getDestIndex())) {
            Plan plan = PopulationUtils.createPlan(person);
            for (int i = 0; i < activities.size(); i++) {
                plan.addActivity(activities.get(i));
                if (i < trips.size()) {
                    plan.getPlanElements().add(trips.get(i));
                }
            }
            plans.add(plan);

            // LOG da sequência
            String seq = activities.stream()
                    .map(a -> a.getLinkId().toString())
                    .collect(Collectors.joining(" -> "));
            System.out.println("[FINAL PLAN] " + seq);
            return;
        }

        // continua expandindo
        for (TripSegment nextSeg : graph.get(currentSeg.getDestIndex())) {
            buildPlansDFS(person,
                    nextSeg,
                    graph,
                    new ArrayList<>(activities),
                    new ArrayList<>(trips),
                    plans);
        }
    }






    public void run(Plan plan) {

        double alpha = stmConfig.getAlpha();
        double omega = stmConfig.getOmega();
        double gamma = stmConfig.getGamma();
        double beta = stmConfig.getBeta(); // parâmetro em minutos
        double delta = stmConfig.getDelta();
        List<Double> arrivalTargets = stmConfig.getArrivalTargets();

        Person person = plan.getPerson();
        List<PlanElement> oldElements = plan.getPlanElements();
        List<PlanElement> newElements = new ArrayList<>();

        //Passo 2 - Gera Pontos de parada dentro do raio Delta
        //Passo 3 - Amostrar paradas de com probabilidade pi(s)
        List<AlternativeTripGenerator.PlanElementInfo> details = altGenerator.extractPlanDetailsWithStops(plan, delta, alpha, gamma);

        List<ChosenStopInfo> boardingWindow = new ArrayList<>(); // cloneWithBoardingWindow(chosen,chosen.getStartTime());

        //Passo 4 - Seleciona as paradas aleatoriamente
        for (AlternativeTripGenerator.PlanElementInfo info : details) {
            System.out.println(info);
            ChosenStopInfo chosen = selectStopForElement(info);
            System.out.println(chosen);
            //Passo 5 - Seleciona novos horários de embarque
            double limit = 5;
            int index = chosen.getIndex() - 1;
            double prevEndTime = index > 0 ? details.get(index - 1).getEndTime() : -1.0;

            for (int i = 1; i <= limit; i++) {
                double startTime = (chosen.getStartTime() - omega * 60) + 2 * i * (omega * 60) / limit;
                double endTime = chosen.getEndTime();
                if (startTime <= endTime && startTime >= prevEndTime) {
                    boardingWindow.add(cloneWithBoardingWindow(chosen, startTime));
                }


            }

        }

        System.out.println("Selecionados " + boardingWindow + "Planos");
        System.out.println("Tamanho " + boardingWindow.size());
        //Passo 6 - Cria planos com segmentos escolhidos
        System.out.println("Tentando criar planos para agente " + person.getId());
        List<Plan> candidates = generateBoardingWindowPlans(person, boardingWindow, tripRouter, arrivalTargets);

        System.out.println("Gerados " + candidates.size() + " planos candidatos.");
        for (Plan p : candidates) {
            System.out.println(planSignature(p));
        }
        Plan finalPlan = null;
        if (!candidates.isEmpty()) {
            Random random = new Random();
            int index = random.nextInt(candidates.size()); // gera índice aleatório
            finalPlan = candidates.get(index);

            System.out.println("[PLAN ESCOLHIDO] Index=" + index +
                    " sequência=" + finalPlan.getPlanElements().stream()
                    .map(pe -> (pe instanceof Activity) ? ((Activity) pe).getType() : "leg")
                    .collect(Collectors.joining(" -> ")));
            // Passo 7 - calcula novo horário de saída e chegada
            logFinalDepartureAndArrival(finalPlan, beta);

            // Passo 8 - adiciona o plano final ao agente e define como selecionado
            String sig = planSignature(finalPlan);
            Plan existing = null;
            for (Plan pl : person.getPlans()) {
                if (planSignature(pl).equals(sig)) {
                    existing = pl;
                    break;
                }
            }

            if (existing != null) {
                person.setSelectedPlan(existing);   // reutiliza plano já existente
                log("[LOG] Plano final já existia, marcado como selecionado.");

            } else {
                person.addPlan(finalPlan);          // adiciona novo plano
                person.setSelectedPlan(finalPlan);  // define como selecionado
                log("[LOG] Plano final adicionado e marcado como selecionado.");
            }
            log("[LOG] Execução concluída para pessoa " + person.getId());
        }

        //Passo 3 - Pontua cada nearbyStop
    }

        /*
        // Passo 2 -  gera alternativas usando delta como raio
        log("[LOG] Gerando alternativas para pessoa " + person.getId() +
                " dentro do raio " + delta);

        List<Plan> alternatives = altGenerator.generateAlternativeTrips(person, plan, delta);

        // Passo 3 -  calcula probabilidade de cada alternativa e guarda em um Map

        Map<Plan, Double> probabilities = calculateAlternativeProbabilities(person, alternatives, alpha, gamma);

        // Passo 4 - seleciona um plano de forma estocástica
        Plan chosenPlan = selectPlanStochastically(probabilities,plan);

        System.out.println("Plano escolhido para pessoa " + person.getId() +
                " foi o plano alternativo com probabilidade " + probabilities.get(chosenPlan));

        // Passo 5 - Filtra por viabilidade

        Set<Plan> solutions = filterNearbyPlans_home(chosenPlan, plan,  arrivalTargets,omega);

        System.out.println("Total de soluções viáveis: " + solutions.size());

        // Passo 6 - escolhe o mais tardio
        /*
        Plan finalPlan = chooseLatestFeasiblePlan(solutions);
        if (finalPlan == null) {
            log("[LOG] Nenhum plano viável encontrado, mantendo plano original.");
            finalPlan = plan;
        }
        */

        // Passo 6 - escolhe aleatoriamente uma das soluções viáveis
                // Passo 6 - mantém o plano escolhido por π se for viável; senão a melhor viável por π
        /*Plan finalPlan;
        if (chosenPlan == plan || solutions.contains(chosenPlan)) {
            finalPlan = chosenPlan;
            log("[LOG] chosenPlan (π) é viável — mantido.");
        } else if (!solutions.isEmpty()) {
            finalPlan = solutions.stream()
                    .max(Comparator.comparingDouble(p -> probabilities.getOrDefault(p, 0.0)))
                    .orElse(plan);
            log("[LOG] chosenPlan inviável; usando melhor alternativa viável por π.");
        } else {
            finalPlan = plan;
            log("[WARN] Nenhuma solução viável; mantendo plano original.");
        }


        // Passo 7 - calcula novo horário de saída e chegada
        logFinalDepartureAndArrival(finalPlan, beta);

         // Passo 8 - adiciona o plano final ao agente e define como selecionado
        String sig = planSignature(finalPlan);
        Plan existing = null;
        for (Plan pl : person.getPlans()) {
            if (planSignature(pl).equals(sig)) {
                existing = pl;
                break;
            }
        }

        if (existing != null) {
            person.setSelectedPlan(existing);   // reutiliza plano já existente
            log("[LOG] Plano final já existia, marcado como selecionado.");

        } else {
            person.addPlan(finalPlan);          // adiciona novo plano
            person.setSelectedPlan(finalPlan);  // define como selecionado
            log("[LOG] Plano final adicionado e marcado como selecionado.");
        }
        log("[LOG] Execução concluída para pessoa " + person.getId());

    }

*/
    // Passo 9 - resumo global da simulação
    public void logResumoSimulacao() {
        log("[LOG] ===== Resumo Global da Simulação =====");
        log("[LOG] Total de planos verificados=" + totalVerificadosGlobal);
        log("[LOG] Total de planos aceitos=" + totalAceitosGlobal);
        log("[LOG] Total de planos rejeitados=" + totalRejeitadosGlobal);

        if (totalVerificadosGlobal > 0) {
            double percAceitos = (100.0 * totalAceitosGlobal) / totalVerificadosGlobal;
            double percRejeitados = (100.0 * totalRejeitadosGlobal) / totalVerificadosGlobal;
            log("[LOG] Percentual aceitos=" + String.format("%.2f", percAceitos) + "%");
            log("[LOG] Percentual rejeitados=" + String.format("%.2f", percRejeitados) + "%");
        }
        log("[LOG] =======================================");
    }



}
