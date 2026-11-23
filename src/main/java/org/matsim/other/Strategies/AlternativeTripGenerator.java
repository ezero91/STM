package org.matsim.other.Strategies;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.vehicles.Vehicle;

import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

public class AlternativeTripGenerator {

    private final TripRouter tripRouter;
    private final Scenario scenario;
    private final TransitOccupancyAnalyzer occupancyAnalyzer;

    private PrintWriter fileLogger = null;

    public AlternativeTripGenerator(Scenario scenario, TripRouter tripRouter, TransitOccupancyAnalyzer occupancyAnalyzer) {
        // Inicializa STMInitializer internamente
        //STMInitializer initializer = new STMInitializer();
        //initializer.init();
        this.scenario = scenario;
        this.tripRouter = tripRouter;

        this.occupancyAnalyzer = occupancyAnalyzer;
    }
    private void log(String msg) {
        System.out.println(msg);
        if (fileLogger != null) fileLogger.println(msg);
    }

    private void error(String msg) {
        System.err.println(msg);
        if (fileLogger != null) fileLogger.println("ERROR: " + msg);
    }

    public List<PlanElementInfo> extractPlanDetailsWithStops(Plan plan, double delta, double alpha, double gamma) {
        List<PlanElementInfo> infos = new ArrayList<>();
        List<PlanElement> elems = plan.getPlanElements();
        TransitSchedule schedule = scenario.getTransitSchedule();
        double dep = -1;
        double travel = -1;
        double arr = -1;



        for (int i = 0; i < elems.size(); i++) {
            PlanElement pe = elems.get(i);

            PlanElement pe2 = i+1>=elems.size() ? elems.get(i): elems.get(i+1);

            if (pe instanceof Activity act) {
                double start = act.getStartTime().isDefined() ? act.getStartTime().seconds() : arr;
                double end   = act.getEndTime().isDefined()   ? act.getEndTime().seconds()   : -1.0;
                if (pe2 instanceof Leg leg) {
                    end   = act.getEndTime().isDefined()   ? act.getEndTime().seconds()   : leg.getDepartureTime().seconds();
                }


                Map<TransitStopFacility, Double> nearbyStops = new HashMap<>();
                if (act.getCoord() != null) {
                    for (TransitStopFacility stop : schedule.getFacilities().values()) {
                        double dist = CoordUtils.calcEuclideanDistance(act.getCoord(), stop.getCoord());
                        if (dist <= delta) {
                            int viableRoutes = getViableRoutesForStop(stop);
                            double occupancy = getCurrentOccupancyForStop(stop);
                            double score = computeStopScore(viableRoutes, occupancy, alpha, gamma);
                            nearbyStops.put(stop, score);
                        }
                    }
                }

                infos.add(new PlanElementInfo(i, "activity-" + act.getType(),
                        act.getLinkId(), start, end, nearbyStops));

            } else if (pe instanceof Leg leg) {
                dep = leg.getDepartureTime().isDefined() ? leg.getDepartureTime().seconds() : -1.0;
                travel = leg.getTravelTime().isDefined() ? leg.getTravelTime().seconds() : 0.0;
                arr = (dep >= 0) ? dep + travel : -1.0;

                infos.add(new PlanElementInfo(i, "leg-" + leg.getMode(),
                        leg.getRoute() != null ? leg.getRoute().getStartLinkId() : null,
                        dep, arr, Collections.emptyMap()));
            }
        }
        return infos;
    }
    // ---------------- Funções auxiliares ----------------

    private int getViableRoutesForStop(TransitStopFacility stop) {
        int count = 0;
        for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                for (TransitRouteStop rs : route.getStops()) {
                    if (rs.getStopFacility().equals(stop)) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    private double getCurrentOccupancyForStop(TransitStopFacility stop) {
        Id<Vehicle> vehicleId = findVehicleServingStop(stop);
        if (vehicleId == null) return 0.0;
        return occupancyAnalyzer.getOccupancy(vehicleId);
    }

    private double computeStopScore(int viableRoutes, double occupancy,double alpha,double gamma) {
        return occupancy > 0 ? ((double) Math.pow(viableRoutes,alpha)  /  Math.pow(1 + occupancy,gamma)) : viableRoutes;
    }

    private Id<Vehicle> findVehicleServingStop(TransitStopFacility stop) {
        TransitSchedule schedule = scenario.getTransitSchedule();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                boolean passesStop = route.getStops().stream()
                        .anyMatch(rs -> rs.getStopFacility().equals(stop));
                if (passesStop && !route.getDepartures().isEmpty()) {
                    Departure dep = route.getDepartures().values().iterator().next();
                    return dep.getVehicleId();
                }
            }
        }
        return null;
    }
    /**
     * Classe auxiliar interna para armazenar informações de cada elemento do plano.
     */
    public static class PlanElementInfo {
        private final int index;
        private final String type; // "activity" ou "leg"
        private final Id<Link> linkId;
        private final double startTime;
        private final double endTime;
        private final Map<TransitStopFacility, Double> nearbyStops;

        public PlanElementInfo(int index, String type, Id<Link> linkId,
                               double startTime, double endTime,
                               Map<TransitStopFacility, Double> nearbyStops) {
            this.index = index;
            this.type = type;
            this.linkId = linkId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.nearbyStops = nearbyStops;
        }

        public int getIndex() { return index; }
        public String getType() { return type; }
        public Id<Link> getLinkId() { return linkId; }
        public double getStartTime() { return startTime; }
        public double getEndTime() { return endTime; }
        public Map<TransitStopFacility, Double> getNearbyStops() { return nearbyStops; }

        @Override
        public String toString() {
            return "PlanElementInfo[index=" + index +
                    ", type=" + type +
                    ", link=" + linkId +
                    ", start=" + startTime +
                    ", end=" + endTime +
                    ", nearbyStops=" + nearbyStops + "]";
        }
    }

    public List<Plan> generateAlternativeTrips(Person person, Plan plan, double delta) {
        log("[LOG] Iniciando geração de alternativas (Passo 1..3) para pessoa " + person.getId() +
                " com raio delta=" + delta);

        List<Plan> alternatives = new ArrayList<>();
        if (plan == null) {
            error("[ERROR] Plano fornecido é null");
            return alternatives;
        }
        List<PlanElement> elements = plan.getPlanElements();
        if (elements == null || elements.isEmpty()) {
            error("[ERROR] Plano sem elementos");
            return alternatives;
        }

        // --- parâmetros de controle para evitar explosão combinatória ---
        final int maxCandidatesPerActivity = 5; // top N stops por activity (ajuste conforme necessário)
        final int maxCombinations = 200; // limite total de combinações a gerar (segurança)

        // --- fallback de departureTime do plano original (primeiro leg) ---
        double globalDepartureFallback = 0.0;
        for (PlanElement pe : elements) {
            if (pe instanceof Leg) {
                globalDepartureFallback = ((Leg) pe).getDepartureTime().orElse(0.0);
                break;
            }
        }
        log("[LOG] departureTime fallback extraído do plano original = " + globalDepartureFallback);

        PopulationFactory factory = scenario.getPopulation().getFactory();

        // --- Passo 1: coletar activities importantes ---
        class ImportantActivity {
            final int index;
            final String type;
            double startTime; // -1 se indefinido
            double endTime;   // -1 se indefinido
            final Coord coord;
            final Id linkId;

            ImportantActivity(int index, String type, double startTime, double endTime,
                              Coord coord, Id linkId) {
                this.index = index;
                this.type = type;
                this.startTime = startTime;
                this.endTime = endTime;
                this.coord = coord;
                this.linkId = linkId;
            }

            @Override
            public String toString() {
                return "ImportantActivity[index=" + index +
                        ", type=" + type +
                        ", start=" + startTime +
                        ", end=" + endTime +
                        ", coord=" + (coord != null ? coord.toString() : "null") +
                        ", link=" + linkId + "]";
            }
        }

        List<ImportantActivity> importantActivities = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            PlanElement pe = elements.get(i);
            if (!(pe instanceof Activity)) continue;
            Activity act = (Activity) pe;
            String type = act.getType();
            if (!"home".equals(type) && !"work".equals(type) && !"school".equals(type)) continue;

            double start = act.getStartTime().isDefined() ? act.getStartTime().seconds() : -1.0;
            double end   = act.getEndTime().isDefined()   ? act.getEndTime().seconds()   : -1.0;
            Coord coord = act.getCoord();
            Id linkId = act.getLinkId();

            importantActivities.add(new ImportantActivity(i, type, start, end, coord, linkId));
        }
        log("[LOG] Encontradas " + importantActivities.size() + " activities importantes (antes de preencher horários).");

        // --- Preencher horários faltantes com base em Legs vizinhos ---
        for (ImportantActivity ia : importantActivities) {
            int idx = ia.index;

            if (ia.startTime < 0) {
                if (idx > 0 && elements.get(idx - 1) instanceof Leg) {
                    Leg prevLeg = (Leg) elements.get(idx - 1);
                    double dep = prevLeg.getDepartureTime().orElse(Double.NaN);
                    double dur = prevLeg.getTravelTime().orElse(Double.NaN);
                    if (!Double.isNaN(dep) && !Double.isNaN(dur)) {
                        ia.startTime = dep + dur;
                        log("[LOG] Preenchido startTime (leg anterior) index=" + idx + " -> " + ia.startTime);
                    }
                }
            }
            if (ia.endTime < 0) {
                if (idx + 1 < elements.size() && elements.get(idx + 1) instanceof Leg) {
                    Leg nextLeg = (Leg) elements.get(idx + 1);
                    double depNext = nextLeg.getDepartureTime().orElse(Double.NaN);
                    if (!Double.isNaN(depNext)) {
                        ia.endTime = depNext;
                        log("[LOG] Preenchido endTime (leg seguinte) index=" + idx + " -> " + ia.endTime);
                    }
                }
            }
            if (ia.startTime < 0) {
                for (int j = idx - 1; j >= 0; j--) {
                    if (elements.get(j) instanceof Leg) {
                        Leg leg = (Leg) elements.get(j);
                        double dep = leg.getDepartureTime().orElse(Double.NaN);
                        double dur = leg.getTravelTime().orElse(Double.NaN);
                        if (!Double.isNaN(dep) && !Double.isNaN(dur)) {
                            ia.startTime = dep + dur;
                            log("[LOG] Preenchido startTime (leg anterior distante) index=" + idx + " -> " + ia.startTime);
                            break;
                        }
                    }
                }
            }
            if (ia.endTime < 0) {
                for (int j = idx + 1; j < elements.size(); j++) {
                    if (elements.get(j) instanceof Leg) {
                        Leg leg = (Leg) elements.get(j);
                        double dep = leg.getDepartureTime().orElse(Double.NaN);
                        if (!Double.isNaN(dep)) {
                            ia.endTime = dep;
                            log("[LOG] Preenchido endTime (leg seguinte distante) index=" + idx + " -> " + ia.endTime);
                            break;
                        }
                    }
                }
            }

            if (ia.startTime < 0 || ia.endTime < 0) {
                log("[WARN] Horário incompleto para activity importante: " + ia);
            } else {
                log("[LOG] Activity importante com horários preenchidos: " + ia);
            }
        }

        // --- Passo 2: para cada ImportantActivity, buscar paradas dentro do raio delta ---
        Collection<TransitStopFacility> stops = scenario.getTransitSchedule().getFacilities().values();
        log("[LOG] Total de pontos de ônibus carregados: " + stops.size());

        // Para cada activity, guarda lista de stops ordenada por distância (top N)
        Map<ImportantActivity, List<TransitStopFacility>> candidatesMap = new HashMap<>();
        for (ImportantActivity ia : importantActivities) {
            List<TransitStopFacility> localCandidates = new ArrayList<>();
            if (ia.coord == null) {
                log("[WARN] Activity sem coordenada, pulando busca de stops: " + ia);
                candidatesMap.put(ia, localCandidates);
                continue;
            }

            // coleta stops dentro do raio
            List<TransitStopFacility> within = new ArrayList<>();
            for (TransitStopFacility stop : stops) {
                if (stop == null || stop.getCoord() == null) continue;
                double dist = CoordUtils.calcEuclideanDistance(ia.coord, stop.getCoord());
                if (dist <= delta) within.add(stop);
            }

            // ordena por distância e pega top N
            within.sort((a, b) -> {
                double da = CoordUtils.calcEuclideanDistance(ia.coord, a.getCoord());
                double db = CoordUtils.calcEuclideanDistance(ia.coord, b.getCoord());
                return Double.compare(da, db);
            });

            for (int i = 0; i < Math.min(maxCandidatesPerActivity, within.size()); i++) {
                localCandidates.add(within.get(i));
            }

            candidatesMap.put(ia, localCandidates);
            log("[LOG] Activity index=" + ia.index + " type=" + ia.type + " -> candidatos encontrados (top " + maxCandidatesPerActivity + ")=" + localCandidates.size());
        }

        // --- Preparar lista limpa de importantActivities (remover duplicatas consecutivas) ---
        List<ImportantActivity> cleaned = new ArrayList<>();
        for (ImportantActivity ia : importantActivities) {
            cleaned.add(ia);
            /*
            if (cleaned.isEmpty() || !cleaned.get(cleaned.size() - 1).type.equals(ia.type)) {
                cleaned.add(ia);
            } else {
                log("[LOG] Ignorando duplicata consecutiva: " + ia);
            }

             */
        }

        if (cleaned.size() < 2) {
            log("[LOG] Menos de duas activities importantes após limpeza; nada a gerar.");
            return alternatives;
        }

        // --- Gerar combinações: produto cartesiano de uma stop por activity (limitado) ---
        List<List<TransitStopFacility>> listsForProduct = new ArrayList<>();
        for (ImportantActivity ia : cleaned) {
            List<TransitStopFacility> list = candidatesMap.getOrDefault(ia, new ArrayList<>());
            if (list.isEmpty()) {
                // se alguma activity não tem candidatos, ainda assim adicionamos lista vazia para indicar impossibilidade
                listsForProduct.add(new ArrayList<>());
            } else {
                listsForProduct.add(list);
            }
        }

        // se qualquer lista estiver vazia, não há combinações válidas
        boolean anyEmpty = listsForProduct.stream().anyMatch(List::isEmpty);
        if (anyEmpty) {
            log("[LOG] Pelo menos uma activity importante não tem candidatos; abortando geração de combinações.");
            return alternatives;
        }

        // função recursiva para gerar combinações (cada combinação é uma lista de TransitStopFacility, um por activity em 'cleaned')
        List<List<TransitStopFacility>> combinations = new ArrayList<>();
        generateCombinationsRecursive(listsForProduct, 0, new ArrayList<>(), combinations, maxCombinations);

        log("[LOG] Combinações geradas: " + combinations.size() + " (limit " + maxCombinations + ")");

        // --- Para cada combinação, roteia segmento a segmento e monta plano completo ---
        int comboIndex = 0;
        for (List<TransitStopFacility> combo : combinations) {
            comboIndex++;
            if (combo.size() != cleaned.size()) {
                log("[WARN] Combinação com tamanho inesperado, pulando.");
                continue;
            }

            // cria novo plano que irá conter todas as activities principais do original
            Plan newPlan = factory.createPlan();

            // adiciona a primeira activity principal original (mantendo tipo/coord/link/endTime se possível)
            ImportantActivity firstIA = cleaned.get(0);
            Activity firstOrig = (Activity) elements.get(firstIA.index);
            Activity firstAct = factory.createActivityFromLinkId(firstOrig.getType(), firstOrig.getLinkId());
            firstAct.setCoord(firstOrig.getCoord());
            if (firstOrig.getEndTime().isDefined()) firstAct.setEndTime(firstOrig.getEndTime().seconds());
            newPlan.addActivity(firstAct);

            boolean failedCombo = false;

            // para cada segmento i -> i+1, roteia entre combo.get(i) e combo.get(i+1)
            for (int seg = 0; seg < combo.size() - 1; seg++) {
                TransitStopFacility stopFrom = combo.get(seg);
                TransitStopFacility stopTo = combo.get(seg + 1);

                ImportantActivity iaFrom = cleaned.get(seg);
                ImportantActivity iaTo = cleaned.get(seg + 1);

                // determina departureTime para o segmento: preferir endTime de iaFrom, senão startTime, senão fallback global
                double departureForSegment = (iaFrom.endTime > 0) ? iaFrom.endTime :
                        (iaFrom.startTime > 0) ? iaFrom.startTime : globalDepartureFallback;

                // determina destination facility a partir do link do stopTo (fallback para link da activity iaTo)
                Facility destFacility = null;
                if (stopTo.getLinkId() != null && scenario.getNetwork().getLinks().get(stopTo.getLinkId()) != null) {
                    destFacility = FacilitiesUtils.wrapLink(scenario.getNetwork().getLinks().get(stopTo.getLinkId()));
                } else if (iaTo.linkId != null && scenario.getNetwork().getLinks().get(iaTo.linkId) != null) {
                    destFacility = FacilitiesUtils.wrapLink(scenario.getNetwork().getLinks().get(iaTo.linkId));
                } else {
                    log("[WARN] Não foi possível determinar destinationFacility para stopTo=" + stopTo.getId() + " -> pulando combinação " + comboIndex);
                    failedCombo = true;
                    break;
                }

                List<? extends PlanElement> segmentTrip = null;
                try {
                    segmentTrip = tripRouter.calcRoute(
                            "pt",
                            stopFrom,
                            destFacility,
                            departureForSegment,
                            person,
                            person.getAttributes()
                    );
                } catch (Exception e) {
                    error("[ERROR] tripRouter.calcRoute falhou para stopFrom=" + stopFrom.getId() +
                            " stopTo=" + stopTo.getId() + " : " + e.getMessage());
                    failedCombo = true;
                    break;
                }

                if (segmentTrip == null || segmentTrip.isEmpty()) {
                    log("[LOG] Nenhuma rota retornada para combinação (segment) " + stopFrom.getId() + " -> " + stopTo.getId() +
                            " (comboIndex=" + comboIndex + ")");
                    failedCombo = true;
                    break;
                }

                // adiciona elementos retornados pelo tripRouter ao plano
                for (PlanElement pe : segmentTrip) {
                    if (pe instanceof Leg) {
                        newPlan.addLeg((Leg) pe);
                    } else if (pe instanceof Activity) {
                        newPlan.addActivity((Activity) pe);
                    }
                }

                // garante que a activity principal 'iaTo' esteja presente após o segmento; se não, adiciona-a
                Activity expectedOrigTo = (Activity) elements.get(iaTo.index);
                boolean hasTo = false;
                for (PlanElement pe : newPlan.getPlanElements()) {
                    if (pe instanceof Activity) {
                        Activity a = (Activity) pe;
                        if (a.getType().equals(expectedOrigTo.getType()) &&
                                a.getLinkId() != null && a.getLinkId().equals(expectedOrigTo.getLinkId())) {
                            hasTo = true;
                            break;
                        }
                    }
                }
                if (!hasTo) {
                    Activity toAct = factory.createActivityFromLinkId(expectedOrigTo.getType(), expectedOrigTo.getLinkId());
                    toAct.setCoord(expectedOrigTo.getCoord());
                    newPlan.addActivity(toAct);
                }
            } // fim loop segmentos

            if (failedCombo) {
                log("[LOG] Combinação " + comboIndex + " falhou; pulando.");
                continue;
            }

            // após todos os segmentos, garante que o plano contenha todas as activities principais originais (na ordem)
            // (já adicionamos a primeira e garantimos cada 'to' após cada segmento; aqui apenas checamos e, se faltar, adicionamos)
            for (ImportantActivity ia : cleaned) {
                Activity orig = (Activity) elements.get(ia.index);
                boolean found = false;
                for (PlanElement pe : newPlan.getPlanElements()) {
                    if (pe instanceof Activity) {
                        Activity a = (Activity) pe;
                        if (a.getType().equals(orig.getType()) &&
                                a.getLinkId() != null && a.getLinkId().equals(orig.getLinkId())) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    Activity a = factory.createActivityFromLinkId(orig.getType(), orig.getLinkId());
                    a.setCoord(orig.getCoord());
                    newPlan.addActivity(a);
                }
            }

            // log resumido e adicionar alternativa
            log("[LOG] Alternativa completa gerada (comboIndex=" + comboIndex + "), elementos=" + newPlan.getPlanElements().size());
            alternatives.add(newPlan);

            // segurança: se já alcançamos o limite de combinações processadas, interrompe
            if (alternatives.size() >= maxCombinations) {
                log("[LOG] Atingido limite de alternativas (maxCombinations=" + maxCombinations + "), interrompendo geração.");
                break;
            }
        } // fim combos

        log("[LOG] Geração concluída. Total de alternativas geradas: " + alternatives.size());
        return alternatives;
    }
    public List<Plan> generateAlternativeTrips_home(Person person, Plan plan, double delta) {
        log("[LOG] Iniciando geração de alternativas (Passo 1..3) para pessoa " + person.getId() +
                " com raio delta=" + delta);

        List<Plan> alternatives = new ArrayList<>();
        if (plan == null) {
            error("[ERROR] Plano fornecido é null");
            return alternatives;
        }
        List<PlanElement> elements = plan.getPlanElements();
        if (elements == null || elements.isEmpty()) {
            error("[ERROR] Plano sem elementos");
            return alternatives;
        }

        // --- parâmetros de controle para evitar explosão combinatória ---
        final int maxCandidatesPerActivity = 5; // top N stops por activity (ajuste conforme necessário)
        final int maxCombinations = 200; // limite total de combinações a gerar (segurança)

        // --- fallback de departureTime do plano original (primeiro leg) ---
        double globalDepartureFallback = 0.0;
        for (PlanElement pe : elements) {
            if (pe instanceof Leg) {
                globalDepartureFallback = ((Leg) pe).getDepartureTime().orElse(0.0);
                break;
            }
        }
        log("[LOG] departureTime fallback extraído do plano original = " + globalDepartureFallback);

        PopulationFactory factory = scenario.getPopulation().getFactory();

        // --- Passo 1: coletar activities importantes ---
        class ImportantActivity {
            final int index;
            final String type;
            double startTime; // -1 se indefinido
            double endTime;   // -1 se indefinido
            final Coord coord;
            final Id linkId;

            ImportantActivity(int index, String type, double startTime, double endTime,
                              Coord coord, Id linkId) {
                this.index = index;
                this.type = type;
                this.startTime = startTime;
                this.endTime = endTime;
                this.coord = coord;
                this.linkId = linkId;
            }

            @Override
            public String toString() {
                return "ImportantActivity[index=" + index +
                        ", type=" + type +
                        ", start=" + startTime +
                        ", end=" + endTime +
                        ", coord=" + (coord != null ? coord.toString() : "null") +
                        ", link=" + linkId + "]";
            }
        }

        List<ImportantActivity> importantActivities = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            PlanElement pe = elements.get(i);
            if (!(pe instanceof Activity)) continue;
            Activity act = (Activity) pe;
            String type = act.getType();
            if (!"home".equals(type) && !"work".equals(type) && !"school".equals(type)) continue;

            double start = act.getStartTime().isDefined() ? act.getStartTime().seconds() : -1.0;
            double end   = act.getEndTime().isDefined()   ? act.getEndTime().seconds()   : -1.0;
            Coord coord = act.getCoord();
            Id linkId = act.getLinkId();

            importantActivities.add(new ImportantActivity(i, type, start, end, coord, linkId));
        }
        log("[LOG] Encontradas " + importantActivities.size() + " activities importantes (antes de preencher horários).");

        // --- Preencher horários faltantes com base em Legs vizinhos ---
        for (ImportantActivity ia : importantActivities) {
            int idx = ia.index;

            if (ia.startTime < 0) {
                if (idx > 0 && elements.get(idx - 1) instanceof Leg) {
                    Leg prevLeg = (Leg) elements.get(idx - 1);
                    double dep = prevLeg.getDepartureTime().orElse(Double.NaN);
                    double dur = prevLeg.getTravelTime().orElse(Double.NaN);
                    if (!Double.isNaN(dep) && !Double.isNaN(dur)) {
                        ia.startTime = dep + dur;
                        log("[LOG] Preenchido startTime (leg anterior) index=" + idx + " -> " + ia.startTime);
                    }
                }
            }
            if (ia.endTime < 0) {
                if (idx + 1 < elements.size() && elements.get(idx + 1) instanceof Leg) {
                    Leg nextLeg = (Leg) elements.get(idx + 1);
                    double depNext = nextLeg.getDepartureTime().orElse(Double.NaN);
                    if (!Double.isNaN(depNext)) {
                        ia.endTime = depNext;
                        log("[LOG] Preenchido endTime (leg seguinte) index=" + idx + " -> " + ia.endTime);
                    }
                }
            }
            if (ia.startTime < 0) {
                for (int j = idx - 1; j >= 0; j--) {
                    if (elements.get(j) instanceof Leg) {
                        Leg leg = (Leg) elements.get(j);
                        double dep = leg.getDepartureTime().orElse(Double.NaN);
                        double dur = leg.getTravelTime().orElse(Double.NaN);
                        if (!Double.isNaN(dep) && !Double.isNaN(dur)) {
                            ia.startTime = dep + dur;
                            log("[LOG] Preenchido startTime (leg anterior distante) index=" + idx + " -> " + ia.startTime);
                            break;
                        }
                    }
                }
            }
            if (ia.endTime < 0) {
                for (int j = idx + 1; j < elements.size(); j++) {
                    if (elements.get(j) instanceof Leg) {
                        Leg leg = (Leg) elements.get(j);
                        double dep = leg.getDepartureTime().orElse(Double.NaN);
                        if (!Double.isNaN(dep)) {
                            ia.endTime = dep;
                            log("[LOG] Preenchido endTime (leg seguinte distante) index=" + idx + " -> " + ia.endTime);
                            break;
                        }
                    }
                }
            }

            if (ia.startTime < 0 || ia.endTime < 0) {
                log("[WARN] Horário incompleto para activity importante: " + ia);
            } else {
                log("[LOG] Activity importante com horários preenchidos: " + ia);
            }
        }

        // --- Passo 2: para cada ImportantActivity, buscar paradas dentro do raio delta ---
        Collection<TransitStopFacility> stops = scenario.getTransitSchedule().getFacilities().values();
        log("[LOG] Total de pontos de ônibus carregados: " + stops.size());

        // Para cada activity, guarda lista de stops ordenada por distância (top N)
        Map<ImportantActivity, List<TransitStopFacility>> candidatesMap = new HashMap<>();
        for (ImportantActivity ia : importantActivities) {
            List<TransitStopFacility> localCandidates = new ArrayList<>();
            if (ia.coord == null) {
                log("[WARN] Activity sem coordenada, pulando busca de stops: " + ia);
                candidatesMap.put(ia, localCandidates);
                continue;
            }

            // coleta stops dentro do raio
            List<TransitStopFacility> within = new ArrayList<>();
            for (TransitStopFacility stop : stops) {
                if (stop == null || stop.getCoord() == null) continue;
                double dist = CoordUtils.calcEuclideanDistance(ia.coord, stop.getCoord());
                if (dist <= delta) within.add(stop);
            }

            // ordena por distância e pega top N
            within.sort((a, b) -> {
                double da = CoordUtils.calcEuclideanDistance(ia.coord, a.getCoord());
                double db = CoordUtils.calcEuclideanDistance(ia.coord, b.getCoord());
                return Double.compare(da, db);
            });

            for (int i = 0; i < Math.min(maxCandidatesPerActivity, within.size()); i++) {
                localCandidates.add(within.get(i));
            }

            candidatesMap.put(ia, localCandidates);
            log("[LOG] Activity index=" + ia.index + " type=" + ia.type + " -> candidatos encontrados (top " + maxCandidatesPerActivity + ")=" + localCandidates.size());
        }

        // --- Preparar lista limpa de importantActivities (remover duplicatas consecutivas) ---
        List<ImportantActivity> cleaned = new ArrayList<>();
        for (ImportantActivity ia : importantActivities) {
            cleaned.add(ia);
            /*
            if (cleaned.isEmpty() || !cleaned.get(cleaned.size() - 1).type.equals(ia.type)) {
                cleaned.add(ia);
            } else {
                log("[LOG] Ignorando duplicata consecutiva: " + ia);
            }

             */
        }

        if (cleaned.size() < 2) {
            log("[LOG] Menos de duas activities importantes após limpeza; nada a gerar.");
            return alternatives;
        }

        // --- Gerar combinações: produto cartesiano de uma stop por activity (limitado) ---
        List<List<TransitStopFacility>> listsForProduct = new ArrayList<>();
        for (ImportantActivity ia : cleaned) {
            List<TransitStopFacility> list = candidatesMap.getOrDefault(ia, new ArrayList<>());
            if (list.isEmpty()) {
                // se alguma activity não tem candidatos, ainda assim adicionamos lista vazia para indicar impossibilidade
                listsForProduct.add(new ArrayList<>());
            } else {
                listsForProduct.add(list);
            }
        }

        // se qualquer lista estiver vazia, não há combinações válidas
        boolean anyEmpty = listsForProduct.stream().anyMatch(List::isEmpty);
        if (anyEmpty) {
            log("[LOG] Pelo menos uma activity importante não tem candidatos; abortando geração de combinações.");
            return alternatives;
        }

        // função recursiva para gerar combinações (cada combinação é uma lista de TransitStopFacility, um por activity em 'cleaned')
        List<List<TransitStopFacility>> combinations = new ArrayList<>();
        generateCombinationsRecursive(listsForProduct, 0, new ArrayList<>(), combinations, maxCombinations);

        log("[LOG] Combinações geradas: " + combinations.size() + " (limit " + maxCombinations + ")");

        // --- Para cada combinação, roteia segmento a segmento e monta plano completo ---
        int comboIndex = 0;
        for (List<TransitStopFacility> combo : combinations) {
            comboIndex++;
            if (combo.size() != cleaned.size()) {
                log("[WARN] Combinação com tamanho inesperado, pulando.");
                continue;
            }

            // cria novo plano que irá conter todas as activities principais do original
            Plan newPlan = factory.createPlan();

            // adiciona a primeira activity principal original (mantendo tipo/coord/link/endTime se possível)
            ImportantActivity firstIA = cleaned.get(0);
            Activity firstOrig = (Activity) elements.get(firstIA.index);
            Activity firstAct = factory.createActivityFromLinkId(firstOrig.getType(), firstOrig.getLinkId());
            firstAct.setCoord(firstOrig.getCoord());
            if (firstOrig.getEndTime().isDefined()) firstAct.setEndTime(firstOrig.getEndTime().seconds());
            newPlan.addActivity(firstAct);

            boolean failedCombo = false;

            // para cada segmento i -> i+1, roteia entre combo.get(i) e combo.get(i+1)
            for (int seg = 0; seg < combo.size() - 1; seg++) {
                TransitStopFacility stopFrom = combo.get(seg);
                TransitStopFacility stopTo = combo.get(seg + 1);

                ImportantActivity iaFrom = cleaned.get(seg);
                ImportantActivity iaTo = cleaned.get(seg + 1);

                // determina departureTime para o segmento: preferir endTime de iaFrom, senão startTime, senão fallback global
                double departureForSegment = (iaFrom.endTime > 0) ? iaFrom.endTime :
                        (iaFrom.startTime > 0) ? iaFrom.startTime : globalDepartureFallback;

                // determina destination facility a partir do link do stopTo (fallback para link da activity iaTo)
                Facility destFacility = null;
                if (stopTo.getLinkId() != null && scenario.getNetwork().getLinks().get(stopTo.getLinkId()) != null) {
                    destFacility = FacilitiesUtils.wrapLink(scenario.getNetwork().getLinks().get(stopTo.getLinkId()));
                } else if (iaTo.linkId != null && scenario.getNetwork().getLinks().get(iaTo.linkId) != null) {
                    destFacility = FacilitiesUtils.wrapLink(scenario.getNetwork().getLinks().get(iaTo.linkId));
                } else {
                    log("[WARN] Não foi possível determinar destinationFacility para stopTo=" + stopTo.getId() + " -> pulando combinação " + comboIndex);
                    failedCombo = true;
                    break;
                }

                List<? extends PlanElement> segmentTrip = null;
                try {
                    segmentTrip = tripRouter.calcRoute(
                            "pt",
                            stopFrom,
                            destFacility,
                            departureForSegment,
                            person,
                            person.getAttributes()
                    );
                } catch (Exception e) {
                    error("[ERROR] tripRouter.calcRoute falhou para stopFrom=" + stopFrom.getId() +
                            " stopTo=" + stopTo.getId() + " : " + e.getMessage());
                    failedCombo = true;
                    break;
                }

                if (segmentTrip == null || segmentTrip.isEmpty()) {
                    log("[LOG] Nenhuma rota retornada para combinação (segment) " + stopFrom.getId() + " -> " + stopTo.getId() +
                            " (comboIndex=" + comboIndex + ")");
                    failedCombo = true;
                    break;
                }

                // adiciona elementos retornados pelo tripRouter ao plano
                for (PlanElement pe : segmentTrip) {
                    if (pe instanceof Leg) {
                        newPlan.addLeg((Leg) pe);
                    } else if (pe instanceof Activity) {
                        newPlan.addActivity((Activity) pe);
                    }
                }

                // garante que a activity principal 'iaTo' esteja presente após o segmento; se não, adiciona-a
                Activity expectedOrigTo = (Activity) elements.get(iaTo.index);
                boolean hasTo = false;
                for (PlanElement pe : newPlan.getPlanElements()) {
                    if (pe instanceof Activity) {
                        Activity a = (Activity) pe;
                        if (a.getType().equals(expectedOrigTo.getType()) &&
                                a.getLinkId() != null && a.getLinkId().equals(expectedOrigTo.getLinkId())) {
                            hasTo = true;
                            break;
                        }
                    }
                }
                if (!hasTo) {
                    Activity toAct = factory.createActivityFromLinkId(expectedOrigTo.getType(), expectedOrigTo.getLinkId());
                    toAct.setCoord(expectedOrigTo.getCoord());
                    newPlan.addActivity(toAct);
                }
            } // fim loop segmentos

            if (failedCombo) {
                log("[LOG] Combinação " + comboIndex + " falhou; pulando.");
                continue;
            }

            // após todos os segmentos, garante que o plano contenha todas as activities principais originais (na ordem)
            // (já adicionamos a primeira e garantimos cada 'to' após cada segmento; aqui apenas checamos e, se faltar, adicionamos)
            for (ImportantActivity ia : cleaned) {
                Activity orig = (Activity) elements.get(ia.index);
                boolean found = false;
                for (PlanElement pe : newPlan.getPlanElements()) {
                    if (pe instanceof Activity) {
                        Activity a = (Activity) pe;
                        if (a.getType().equals(orig.getType()) &&
                                a.getLinkId() != null && a.getLinkId().equals(orig.getLinkId())) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    Activity a = factory.createActivityFromLinkId(orig.getType(), orig.getLinkId());
                    a.setCoord(orig.getCoord());
                    newPlan.addActivity(a);
                }
            }

            // log resumido e adicionar alternativa
            log("[LOG] Alternativa completa gerada (comboIndex=" + comboIndex + "), elementos=" + newPlan.getPlanElements().size());
            alternatives.add(newPlan);

            // segurança: se já alcançamos o limite de combinações processadas, interrompe
            if (alternatives.size() >= maxCombinations) {
                log("[LOG] Atingido limite de alternativas (maxCombinations=" + maxCombinations + "), interrompendo geração.");
                break;
            }
        } // fim combos

        log("[LOG] Geração concluída. Total de alternativas geradas: " + alternatives.size());
        return alternatives;
    }

    // --- auxiliar: retorna as N paradas mais próximas de uma coordenada, dentro do raio delta ---
    private List<TransitStopFacility> findNearestStops(Coord coord, Collection<TransitStopFacility> stops,
                                                       double delta, int maxCandidates) {
        List<TransitStopFacility> result = new ArrayList<>();
        if (coord == null) return result;

        List<TransitStopFacility> within = new ArrayList<>();
        for (TransitStopFacility stop : stops) {
            if (stop == null || stop.getCoord() == null) continue;
            double dist = CoordUtils.calcEuclideanDistance(coord, stop.getCoord());
            if (dist <= delta) within.add(stop);
        }

        within.sort((a, b) -> Double.compare(
                CoordUtils.calcEuclideanDistance(coord, a.getCoord()),
                CoordUtils.calcEuclideanDistance(coord, b.getCoord())));

        for (int i = 0; i < Math.min(maxCandidates, within.size()); i++) {
            result.add(within.get(i));
        }
        return result;
    }

    // --- auxiliar: adiciona um PlanElement já existente (sem modificar) ao novo plano ---
    private void addExistingElement(Plan newPlan, PlanElement pe) {
        if (pe instanceof Activity) {
            newPlan.addActivity((Activity) pe);
        } else if (pe instanceof Leg) {
            newPlan.addLeg((Leg) pe);
        }
    }

    // --- auxiliar: retorna as N paradas mais próximas de uma coordenada, dentro do raio delta ---


    /**
     * Gera combinações recursivamente (produto cartesiano) com limite máximo.
     * lists: lista de listas (cada sublista são candidatos para uma activity)
     * pos: posição atual
     * current: combinação parcial
     * out: lista de combinações completas
     * max: limite de combinações a gerar
     */
    private void generateCombinationsRecursive(List<List<TransitStopFacility>> lists, int pos,
                                               List<TransitStopFacility> current,
                                               List<List<TransitStopFacility>> out,
                                               int max) {
        if (out.size() >= max) return;
        if (pos == lists.size()) {
            out.add(new ArrayList<>(current));
            return;
        }
        List<TransitStopFacility> list = lists.get(pos);
        for (TransitStopFacility item : list) {
            current.add(item);
            generateCombinationsRecursive(lists, pos + 1, current, out, max);
            current.remove(current.size() - 1);
            if (out.size() >= max) return;
        }
    }
}
