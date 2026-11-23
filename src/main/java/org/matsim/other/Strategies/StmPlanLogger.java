package org.matsim.other.Strategies;

import com.google.inject.Inject;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.routes.TransitPassengerRoute;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Per-iteration, per-agent log of: plano inicial (iteration 0), planos
 * possiveis (the agent's plan memory with scores), and plano escolhido
 * (the selected plan). Writes <output>/stm_plan_log.txt.
 */
public class StmPlanLogger implements IterationEndsListener {

    private final Population population;
    private final String logPath;
    private final int maxAgents = 60;   // subset-sized; caps a full-population run

    @Inject
    public StmPlanLogger(Population population, OutputDirectoryHierarchy io) {
        this.population = population;
        this.logPath = io.getOutputFilename("stm_plan_log.txt");
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        int it = event.getIteration();
        // truncate on iteration 0, append afterwards
        try (PrintWriter w = new PrintWriter(new FileWriter(logPath, it != 0))) {
            w.println("======== ITERATION " + it + " ========");
            int n = 0;
            for (Person person : population.getPersons().values()) {
                if (n++ >= maxAgents) break;
                logPerson(w, person, it);
            }
        } catch (IOException e) {
            System.err.println("StmPlanLogger: " + e.getMessage());
        }
    }

    private void logPerson(PrintWriter w, Person person, int it) {
        Object subpop = person.getAttributes().getAttribute("subpopulation");
        Plan selected = person.getSelectedPlan();

        w.println("=== " + person.getId() + "  [" + subpop + "] ===");
        if (it == 0) {
            w.println("  inicial   : " + summarize(selected));
        }
        w.println("  possiveis (" + person.getPlans().size() + "):");
        for (Plan plan : person.getPlans()) {
            String mark = (plan == selected) ? "*" : " ";
            w.println("    [" + mark + "] score=" + plan.getScore() + " : " + summarize(plan));
        }
        w.println("  escolhido : score=" + selected.getScore() + " : " + summarize(selected));
    }

    private String summarize(Plan plan) {
        StringBuilder sb = new StringBuilder();
        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity) {
                Activity a = (Activity) pe;
                sb.append(a.getType());
                if (a.getEndTime().isDefined()) {
                    sb.append("@").append(Time.writeTime(a.getEndTime().seconds()));
                }
                sb.append(" ");
            } else if (pe instanceof Leg) {
                Leg leg = (Leg) pe;
                sb.append("--").append(leg.getMode());
                if (leg.getRoute() instanceof TransitPassengerRoute) {
                    TransitPassengerRoute r = (TransitPassengerRoute) leg.getRoute();
                    sb.append("[").append(r.getLineId()).append("/").append(r.getRouteId())
                      .append(" ").append(r.getAccessStopId()).append("→").append(r.getEgressStopId()).append("]");
                }
                sb.append("--> ");
            }
        }
        return sb.toString().trim();
    }
}