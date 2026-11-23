package org.matsim.other.Strategies;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

@Singleton
public class StmDataProvider implements
        PersonEntersVehicleEventHandler,
        PersonLeavesVehicleEventHandler,
        VehicleArrivesAtFacilityEventHandler,
        TransitDriverStartsEventHandler,
        AfterMobsimListener {

    // estrutural (schedule): nº de rotas por parada — NÃO muda com a simulação
    private final Map<String, Integer> nRoutes = new HashMap<>();

    // loading(s,t) que o STM lê: começa com o seed do STM_INFO, trocado a cada iteração
    private volatile Map<String, NavigableMap<Integer, Integer>> activeLoading;

    // acumulador da iteração corrente (dos eventos do mobsim)
    private Map<String, NavigableMap<Integer, Integer>> current = new HashMap<>();

    private final Map<Id<Vehicle>, Integer> occupancy = new HashMap<>();  // ocupação viva
    private final Set<Id<Person>> drivers = new HashSet<>();               // motoristas (não contam)

    @Inject
    public StmDataProvider(Config config) {
        STMConfigGroup g = (STMConfigGroup) config.getModules().get(STMConfigGroup.GROUP_NAME);
        String dir = resolveDir(config, g.getStmInfoDir());
        loadRoutes(dir + "/stm_stop_routes.csv");
        this.activeLoading = loadSeedLoading(dir + "/stm_stop_loading.csv");
        System.out.println("[STM_INFO] seed: n_routes=" + nRoutes.size()
                + " paradas, loading=" + activeLoading.size() + " paradas");
    }


    public int getNRoutes(Id<TransitStopFacility> stopId) {
        return nRoutes.getOrDefault(baseId(stopId), 0);
    }

    public double getLoading(Id<TransitStopFacility> stopId, double timeSec) {
        NavigableMap<Integer, Integer> series = activeLoading.get(baseId(stopId));
        if (series == null || series.isEmpty()) return 1.0;
        int t = (int) timeSec;
        Integer floor = series.floorKey(t);
        Integer ceil  = series.ceilingKey(t);
        Integer chosen = (floor == null) ? ceil
                       : (ceil == null)  ? floor
                       : (t - floor <= ceil - t) ? floor : ceil;
        double v = series.get(chosen);
        return v > 0 ? v : 1.0;
    }

    // ---------- eventos: recomputa loading a cada iteração ----------

    @Override public void handleEvent(TransitDriverStartsEvent e) { drivers.add(e.getDriverId()); }

    @Override public void handleEvent(PersonEntersVehicleEvent e) {
        if (drivers.contains(e.getPersonId())) return;
        occupancy.merge(e.getVehicleId(), 1, Integer::sum);
    }

    @Override public void handleEvent(PersonLeavesVehicleEvent e) {
        if (drivers.contains(e.getPersonId())) return;
        occupancy.merge(e.getVehicleId(), -1, Integer::sum);
    }

    @Override public void handleEvent(VehicleArrivesAtFacilityEvent e) {
        int occ = occupancy.getOrDefault(e.getVehicleId(), 0);
        current.computeIfAbsent(baseId(e.getFacilityId()), k -> new TreeMap<>())
               .merge((int) e.getTime(), occ, Math::max);
    }

    @Override public void reset(int iteration) {
        occupancy.clear();
        drivers.clear();
        current = new HashMap<>();
    }

    @Override public void notifyAfterMobsim(AfterMobsimEvent event) {
        if (!current.isEmpty()) {
            this.activeLoading = current;   // passa a usar o loading SIMULADO desta iteração
            System.out.println("[STM_INFO] loading atualizado pela simulação (it "
                    + event.getIteration() + "): " + current.size() + " paradas");
        }
    }

    // ---------- helpers ----------

    private static String baseId(Id<?> id) {
        if (id == null) return null;
        String s = id.toString();
        int i = s.indexOf(".link");
        return (i >= 0) ? s.substring(0, i) : s;
    }

    private void loadRoutes(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split(",");
                nRoutes.put(p[0].trim(), Integer.parseInt(p[1].trim()));
            }
        } catch (Exception e) { System.err.println("StmDataProvider: stop_routes: " + e.getMessage()); }
    }

    private Map<String, NavigableMap<Integer, Integer>> loadSeedLoading(String path) {
        Map<String, NavigableMap<Integer, Integer>> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split(",");
                map.computeIfAbsent(p[0].trim(), k -> new TreeMap<>())
                   .put(Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
            }
        } catch (Exception e) { System.err.println("StmDataProvider: stop_loading: " + e.getMessage()); }
        return map;
    }

    private static String resolveDir(Config config, String dir) {
        java.io.File f = new java.io.File(dir);
        if (!f.isAbsolute() && config.getContext() != null) {
            try {
                java.io.File cfg = new java.io.File(config.getContext().toURI());
                f = new java.io.File(cfg.getParentFile(), dir);
            } catch (Exception e) { /* fallback: relativo ao CWD */ }
        }
        return f.getPath();
    }
}