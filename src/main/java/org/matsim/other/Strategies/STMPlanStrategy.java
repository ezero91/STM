package org.matsim.other.Strategies;

import com.google.inject.Inject;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.router.TripRouter;
import org.matsim.core.config.Config;


/**
 * Wrapper PlanStrategy que delega a mutação para STMStrategyModule.
 * O STMStrategyModule é criado uma vez no construtor e reutilizado.
 */
public class STMPlanStrategy implements PlanStrategy {

    private final TripRouter tripRouter;
    private final Config config;
    private final Scenario scenario;
    private final STMStrategyModule stmModule;
    private final TransitOccupancyAnalyzer occupancyAnalyzer;

    @Inject
    public STMPlanStrategy(TripRouter tripRouter, Config config, Scenario scenario, StmDataProvider stmData, TransitOccupancyAnalyzer occupancyAnalyzer) {
        this.tripRouter = tripRouter;
        this.config = config;
        this.scenario = scenario;
        // cria o módulo uma vez; evita overhead e garante que o módulo tenha acesso ao Config
        this.stmModule = new STMStrategyModule(this.tripRouter, this.config, this.scenario, stmData,occupancyAnalyzer);
        this.occupancyAnalyzer = occupancyAnalyzer;
    }

    @Override
    public void run(HasPlansAndId<Plan, Person> plansContainer) {
        Plan plan = plansContainer.getSelectedPlan();
        // delega a mutação/validação ao módulo
        stmModule.run(plan);
        stmModule.logResumoSimulacao();
    }

    @Override
    public void init(ReplanningContext replanningContext) {
        // opcional: inicialização por replanningContext se necessário
    }

    @Override
    public void finish() {
        // cleanup se necessário
    }
}
