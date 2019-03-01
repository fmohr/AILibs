package de.upb.crc901.mlplan.core;

import java.util.concurrent.TimeoutException;

import org.nd4j.linalg.primitives.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.upb.crc901.mlpipeline_evaluation.CacheEvaluatorMeasureBridge;
import de.upb.crc901.mlplan.multiclass.wekamlplan.ClassifierFactory;
import hasco.exceptions.ComponentInstantiationFailedException;
import hasco.model.ComponentInstance;
import jaicore.basic.ILoggingCustomizable;
import jaicore.basic.IObjectEvaluator;
import jaicore.basic.algorithm.exceptions.ObjectEvaluationFailedException;
import jaicore.concurrent.TimeoutTimer;
import jaicore.concurrent.TimeoutTimer.TimeoutSubmitter;
import jaicore.ml.evaluation.evaluators.weka.AbstractEvaluatorMeasureBridge;
import jaicore.ml.evaluation.evaluators.weka.MonteCarloCrossValidationEvaluator;
import weka.classifiers.Classifier;
import weka.core.Instances;

public class SearchPhasePipelineEvaluator implements IObjectEvaluator<ComponentInstance, Double>, ILoggingCustomizable {

	private Logger logger = LoggerFactory.getLogger(SearchPhasePipelineEvaluator.class);

	private final ClassifierFactory classifierFactory;
	private final AbstractEvaluatorMeasureBridge<Double, Double> evaluationMeasurementBridge;
	private final int seed;
	private final int numMCIterations;
	private final Instances dataShownToSearch;
	private final double trainFoldSize;
	private final IObjectEvaluator<Classifier, Double> searchBenchmark;
	private final int timeoutForSolutionEvaluation;

	public SearchPhasePipelineEvaluator(ClassifierFactory classifierFactory, AbstractEvaluatorMeasureBridge<Double, Double> evaluationMeasurementBridge, int numMCIterations, Instances dataShownToSearch, double trainFoldSize, int seed,
			int timeoutForSolutionEvaluation) {
		super();
		this.classifierFactory = classifierFactory;
		this.evaluationMeasurementBridge = evaluationMeasurementBridge;
		this.seed = seed;
		this.dataShownToSearch = dataShownToSearch;
		this.numMCIterations = numMCIterations;
		this.trainFoldSize = trainFoldSize;
		this.searchBenchmark = new MonteCarloCrossValidationEvaluator(this.evaluationMeasurementBridge, numMCIterations, dataShownToSearch, trainFoldSize, seed);
		this.timeoutForSolutionEvaluation = timeoutForSolutionEvaluation;
	}

	@Override
	public String getLoggerName() {
		return logger.getName();
	}

	@Override
	public void setLoggerName(String name) {
		logger.info("Switching logger name from {} to {}", logger.getName(), name);
		logger = LoggerFactory.getLogger(name);
		if (searchBenchmark instanceof ILoggingCustomizable) {
			logger.info("Setting logger name of actual benchmark {} to {}", searchBenchmark.getClass().getName(), name + ".benchmark");
			((ILoggingCustomizable) searchBenchmark).setLoggerName(name + ".benchmark");
		} else
			logger.info("Benchmark {} does not implement ILoggingCustomizable, not customizing its logger.", searchBenchmark.getClass().getName());
	}

	@Override
	public Double evaluate(ComponentInstance c) throws TimeoutException, InterruptedException, ObjectEvaluationFailedException {
		final AtomicBoolean controlledInterrupt = new AtomicBoolean(false);
		TimeoutSubmitter sub = TimeoutTimer.getInstance().getSubmitter();
		int task = sub.interruptMeAfterMS(timeoutForSolutionEvaluation, () -> {
			controlledInterrupt.set(true);
		});
		try {
			if (this.evaluationMeasurementBridge instanceof CacheEvaluatorMeasureBridge) {
				CacheEvaluatorMeasureBridge bridge = ((CacheEvaluatorMeasureBridge) this.evaluationMeasurementBridge).getShallowCopy(c);
				long seed = this.seed + c.hashCode();
				IObjectEvaluator<Classifier, Double> copiedSearchBenchmark = new MonteCarloCrossValidationEvaluator(bridge, numMCIterations, this.dataShownToSearch, trainFoldSize, seed);
				return copiedSearchBenchmark.evaluate(classifierFactory.getComponentInstantiation(c));
			}
			return searchBenchmark.evaluate(classifierFactory.getComponentInstantiation(c));
		} catch (InterruptedException e) {
			if (controlledInterrupt.get()) {
				Thread.interrupted(); // reset thread interruption flag, because the thread is not really interrupted but should only stop the evaluation
				throw new ObjectEvaluationFailedException("Evaluation of composition failed since the timeout was hit.");
			}
			throw e;
		} catch (ComponentInstantiationFailedException e) {
			throw new ObjectEvaluationFailedException(e, "Evaluation of composition failed as the component instantiation could not be built.");
		} finally {
			sub.cancelTimeout(task);
			logger.debug("Canceled timeout job {}", task);
		}
	}

}