package org.matsim.project;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.apache.logging.log4j.core.tools.picocli.CommandLine;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.replanning.PlanStrategyModule;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.selectors.RandomPlanSelector;
import org.matsim.core.replanning.modules.ReRoute;
import org.matsim.core.replanning.strategies.TimeAllocationMutatorReRoute;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.other.Strategies.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;


@CommandLine.Command(header = ":: MyScenario ::", version = "1.0")
public class MatsimModelImplementation extends MATSimApplication {

	public MatsimModelImplementation() {
		super();
	}

	public static void main(String[] args) {
		String pastaOrigem;
		String pastaDestino;


		for (int i=1;i<=20;i++) {
			MATSimApplication.execute(
					MatsimModelImplementation.class,
					"--config",
					"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_STM9.xml"
			);
			pastaOrigem="D:/Mestrado/MATsim/matsim-example-project-master/output_STM_9/";
			pastaDestino="D:/Mestrado/MATsim/Resultados 1/STM9/"+i ;
			GerenciadorArquivos.copiarPasta(pastaOrigem,pastaDestino);
		}
			/*
			MATSimApplication.execute(
					MatsimModelImplementation.class,
					"--config",
					"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_STM2.xml"
			);
			MATSimApplication.execute(
					MatsimModelImplementation.class,
					"--config",
					"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_STM3.xml"
			);
			MATSimApplication.execute(
					MatsimModelImplementation.class,
					"--config",
					"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_STM4.xml"
			);
			MATSimApplication.execute(
					MatsimModelImplementation.class,
					"--config",
					"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_STM5.xml"
			);
			//pastaOrigem="D:/Mestrado/MATsim/matsim-example-project-master/output_STM_1/";
			//pastaDestino="D:/Mestrado/MATsim/Resultados 1/STM1/"+i ;
			//GerenciadorArquivos.copiarPasta(pastaOrigem,pastaDestino);

			pastaOrigem="D:/Mestrado/MATsim/matsim-example-project-master/output_STM_2/";
			pastaDestino="D:/Mestrado/MATsim/Resultados 1/STM2/"+i ;
			GerenciadorArquivos.copiarPasta(pastaOrigem,pastaDestino);

			pastaOrigem="D:/Mestrado/MATsim/matsim-example-project-master/output_STM_3/";
			pastaDestino="D:/Mestrado/MATsim/Resultados 1/STM3/"+i ;
			GerenciadorArquivos.copiarPasta(pastaOrigem,pastaDestino);

			pastaOrigem="D:/Mestrado/MATsim/matsim-example-project-master/output_STM_4/";
			pastaDestino="D:/Mestrado/MATsim/Resultados 1/STM4/"+i ;
			GerenciadorArquivos.copiarPasta(pastaOrigem,pastaDestino);

			pastaOrigem="D:/Mestrado/MATsim/matsim-example-project-master/output_STM_5/";
			pastaDestino="D:/Mestrado/MATsim/Resultados 1/STM5/"+i ;
			GerenciadorArquivos.copiarPasta(pastaOrigem,pastaDestino);
		} */
		/*
		MATSimApplication.execute(
				MatsimModelImplementation.class,
				"--config",
				"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_STM.xml"
		);

		MATSimApplication.execute(
				MatsimModelImplementation.class,
				"--config",
				"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_STM.xml"
		);

		*/
		/*
		MATSimApplication.execute(
				MatsimModelImplementation.class,
				"--config",
				"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_ReRoute.xml"
		);
		MATSimApplication.execute(
				MatsimModelImplementation.class,
				"--config",
				"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_TimeAllocation.xml"
		);
		MATSimApplication.execute(
				MatsimModelImplementation.class,
				"--config",
				"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_Misto1.xml"
		);
		MATSimApplication.execute(
				MatsimModelImplementation.class,
				"--config",
				"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_Misto2.xml"
		);
		MATSimApplication.execute(
				MatsimModelImplementation.class,
				"--config",
				"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config_Misto3.xml"
		);
		*/
		/*MATSimApplication.execute(
				MatsimModelImplementation.class,
				"--config",
				"scenarios/pt-simulation-0116_0117_0136_0137_013701_0138_0708_20250207/config.xml"
		);

		 */

	}

	@Override
	protected Config prepareConfig(Config config) {
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		// Adiciona o grupo STM ao config
		config.addModule(new STMConfigGroup());
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		// Aqui você pode modificar o cenário se necessário
	}

	@Override
	protected void prepareControler(Controler controler) {

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(StmDataProvider.class).asEagerSingleton();
				bind(TransitOccupancyAnalyzer.class).asEagerSingleton();
				addEventHandlerBinding().to(StmDataProvider.class);
				addControllerListenerBinding().to(StmDataProvider.class);
				addPlanStrategyBinding("STMStrategy").to(STMPlanStrategy.class);
				addControllerListenerBinding().to(StmPlanLogger.class);
			}
		});
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addPlanStrategyBinding("TimeAllocationMutator_ReRoute")
						.toProvider(TimeAllocationMutatorReRoute.class);
			}
		});
	}
	public class GerenciadorArquivos {

		// Função que recebe as Strings dos caminhos
		public static void copiarArquivo(String caminhoOrigem, String pastaDestino) {
			Path origem = Paths.get(caminhoOrigem);
			// Pega o nome do arquivo original (ex: "foto.jpg")
			Path nomeArquivo = origem.getFileName();

			// Combina a pasta de destino com o nome do arquivo
			Path destino = Paths.get(pastaDestino).resolve(nomeArquivo);

			try {
				// Cria as pastas de destino se elas não existirem
				Files.createDirectories(destino.getParent());

				Files.copy(origem, destino, StandardCopyOption.REPLACE_EXISTING);
				System.out.println("Arquivo copiado para a pasta" + pastaDestino + "com sucesso!");
			} catch (IOException e) {
				System.out.println("Erro ao copiar o arquivo: " + e.getMessage());
			}
		}

		public static void copiarPasta(String pastaOrigem, String pastaDestino) {
			String arquivo = "modestats.csv";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "ph_modestats.csv";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "pkm_modestats.csv";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "scorestats.csv";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "stopwatch.csv";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "traveldistancestats.csv";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "modeChoiceCoverage1x.txt";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "modeChoiceCoverage5x.txt";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "modeChoiceCoverage10x.txt";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "ITERS/it.10/10.legdurations.txt";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);
			arquivo = "ITERS/it.10/10.legHistogram.txt";
			GerenciadorArquivos.copiarArquivo(pastaOrigem  + arquivo,pastaDestino);

		}
		public static void main(String[] args) {
			// Exemplo de como usar a função:
			String de = "C:/pasta1/documento.pdf";
			String para = "C:/pasta2/documento_copia.pdf";

			copiarArquivo(de, para);
		}
	}

}
