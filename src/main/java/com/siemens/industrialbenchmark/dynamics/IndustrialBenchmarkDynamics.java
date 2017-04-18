/*
Copyright 2016 Siemens AG.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.siemens.industrialbenchmark.dynamics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.commons.collections.buffer.CircularFifoBuffer;
import org.apache.commons.math3.random.RandomDataGenerator;

import com.google.common.base.Preconditions;
import com.siemens.industrialbenchmark.datavector.action.ActionAbsolute;
import com.siemens.industrialbenchmark.datavector.action.ActionDelta;
import com.siemens.industrialbenchmark.datavector.action.EffectiveAction;
import com.siemens.industrialbenchmark.datavector.state.MarkovianState;
import com.siemens.industrialbenchmark.datavector.state.MarkovianStateDescription;
import com.siemens.industrialbenchmark.datavector.state.ObservableState;
import com.siemens.industrialbenchmark.datavector.state.ObservableStateDescription;
import com.siemens.industrialbenchmark.dynamics.goldstone.GoldstoneEnvironment;
import com.siemens.industrialbenchmark.externaldrivers.setpointgen.SetPointGenerator;
import com.siemens.industrialbenchmark.properties.PropertiesException;
import com.siemens.industrialbenchmark.properties.PropertiesUtil;
import com.siemens.rl.interfaces.DataVector;
import com.siemens.rl.interfaces.Environment;
import com.siemens.rl.interfaces.ExternalDriver;

/**
 * Basic dynamics of the industrial benchmark.
 * The following steps are contained;
 * <ul>
 *	<li> update setpoint: the setpoint influences the state and changes according to a random walk, generated by a {@link SetPointGenerator}</li>
 *	<li> the influence of the actions is added to the state</li>
 * 	<li> operational costs are computed</li>
 * 	<li> operational costs are delayed, and a convoluted operational cost level is calculated</li>
 * </ul>
 *
 * @author Siegmund Duell, Alexander Hentschel, Michel Tokic
 */
public class IndustrialBenchmarkDynamics implements Environment {

	//private Logger mLogger = Logger.getLogger(IndustrialBenchmarkDynamics.class);
	private final float stepSizeVelocity;
	private final float stepSizeGain;

	/** Ring Buffer of fixed size implementing a FIFO queue */
	private CircularFifoBuffer mOperationalCostsBuffer;

	private float[] mEmConvWeights;
	private boolean convToInit = true;

	private final GoldstoneEnvironment gsEnvironment;
	private static final float MAX_REQUIRED_STEP = (float) Math.sin(15.0f/180.0f*Math.PI);
	private static final float GS_BOUND = 1.5f;
	private static final float GS_SET_POINT_DEPENDENCY = 0.02f;

	private enum C {
		DGain, DVelocity, DSetPoint,
		CostSetPoint, CostGain, CostVelocity, DBase,
		STEP_SIZE_GAIN, STEP_SIZE_VELOCITY
	}

	protected MarkovianState markovState;
	protected MarkovianState mMax;
	protected MarkovianState mMin;
	protected final Properties mProperties;

	private IndustrialBenchmarkRewardFunction mRewardCore;
	private final RandomDataGenerator rda;
	private long randomSeed;
	private float crgs;

	private final List<ExternalDriver> externalDrivers;
	private final ActionDelta zeroAction = new ActionDelta(0, 0, 0);

	/**
	 * Constructor with configuration Properties
	 * @param aProperties The properties objects
	 * @param externalDrivers The list containing external drivers
	 * @throws PropertiesException
	 */
	public IndustrialBenchmarkDynamics(final Properties aProperties, final List<ExternalDriver> externalDrivers) throws PropertiesException {
		this.mProperties = aProperties;
		this.mRewardCore = new IndustrialBenchmarkRewardFunction(aProperties);
		this.stepSizeGain = PropertiesUtil.getFloat(mProperties, C.STEP_SIZE_GAIN.name(), true);
		this.stepSizeVelocity = PropertiesUtil.getFloat(mProperties, C.STEP_SIZE_VELOCITY.name(), true);

		if (externalDrivers == null) {
			this.externalDrivers = new ArrayList<>(1);
			this.externalDrivers.add(new SetPointGenerator(mProperties));
		} else {
			this.externalDrivers = new ArrayList<>(externalDrivers);
		}
		this.gsEnvironment = new GoldstoneEnvironment(24, MAX_REQUIRED_STEP, MAX_REQUIRED_STEP / 2.0);
		this.rda = new RandomDataGenerator();

		init();
		step(zeroAction);
	}

	/**
	 * Constructor with configuration Properties
	 * @param aProperties The properties objects
	 * @throws PropertiesException
	 */
	public IndustrialBenchmarkDynamics(final Properties aProperties) throws PropertiesException {
		this(aProperties, null);
	}

	/**
	 * initialize the industrial benchmark
	 * @throws PropertiesException
	 */
	protected void init() throws PropertiesException {

		// configure convolution variables
		crgs = PropertiesUtil.getFloat(mProperties, "CRGS", true);
		mEmConvWeights = getFloatArray(mProperties.getProperty("ConvArray"));
		final List<String> markovStateAdditionalNames = new ArrayList<>();
		mOperationalCostsBuffer = new CircularFifoBuffer(mEmConvWeights.length);
		for (int i = 0; i < mEmConvWeights.length; i++) {
			mOperationalCostsBuffer.add(0.0d); // initialize all operationalcosts with zero
			markovStateAdditionalNames.add("OPERATIONALCOST_" + i); // add operationalcost_lag to list of convoluted markov variables
		}
		markovStateAdditionalNames.addAll(MarkovianStateDescription.getNonConvolutedInternalVariables());

		// add variables from external driver
		final List<String> extNames = new ArrayList<>();
		for (final ExternalDriver d : externalDrivers) {
			for (final String n : d.getState().getKeys()) {
				if (!extNames.contains(n) && !markovStateAdditionalNames.contains(n)) {
					extNames.add(n);
				}
			}
		}
		markovStateAdditionalNames.addAll(extNames);
		//markovStateAdditionalNames.addAll(extDriver.getState().getKeys());

		// instantiate markov state with additional convolution variable names
		markovState = new MarkovianState(markovStateAdditionalNames);
		mMin = new MarkovianState(markovStateAdditionalNames); // lower variable boundaries
		mMax = new MarkovianState(markovStateAdditionalNames); // upper variable boundaries

		// extract variable boundings + initial values from Properties
		for (final String v : markovState.getKeys()) {
			final float init = PropertiesUtil.getFloat(mProperties, v + "_INIT", 0);
			final float max = PropertiesUtil.getFloat(mProperties, v + "_MAX", Float.MAX_VALUE);
			final float min = PropertiesUtil.getFloat(mProperties, v + "_MIN", -Float.MAX_VALUE);
			Preconditions.checkArgument(max > min, "variable=%s: max=%s must be > than min=%s", v, max, min);
			Preconditions.checkArgument(init >= min && init <= max,  "variable=%s: init=%s must be between min=%s and max=%s", v, init, min, max);
			mMax.setValue(v, max);
			mMin.setValue(v, min);
			markovState.setValue(v, init);
		}

		// seed all random number generators for allowing to re-conduct the experiment
		randomSeed = PropertiesUtil.getLong(mProperties, "SEED", System.currentTimeMillis());
		//mLogger.debug("init seed: " + randomSeed);
		rda.reSeed(randomSeed);

		//extDriver.setSeed(rda.nextLong(0, Long.MAX_VALUE));
		for (final ExternalDriver d : externalDrivers) {
			d.setSeed(rda.nextLong(0, Long.MAX_VALUE));
			d.filter(markovState);
		}

		// set all NaN values to 0.0
		for (final String key : markovState.getKeys()) {
			if (Double.isNaN(markovState.getValue(key))) {
				markovState.setValue(key, 0.0);
			}
		}

		//for (final String key : markovState.getKeys()) {
		//	mLogger.debug(key  + "=" + markovState.getValue(key));
		//}
		//System.exit(-1);
		//mRewardCore.setNormal(rda);
	}

	/**
	 * converts a float array represented as a string (e.g. "0.01, 0.2, 0.9") to a Java float[]
	 * @param aFloatArrayAsString The float array represented as a String
	 * @return The float[] array
	 */
	private static float[] getFloatArray(final String aFloatArrayAsString) {
		// remove all whitespace
		final String components = aFloatArrayAsString.replaceAll("( |\t|\n)", "");
		final String[] split = components.split(",");
		final float[] result = new float[split.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = Float.parseFloat(split[i]);
		}
		return result;
	}

	/**
	 * Returns the observable components from the markovian state.
	 *
	 * @return current state of the industrial benchmark
	 */
	@Override
	public ObservableState getState() {
		final ObservableState s = new ObservableState();
		for (final String key : s.getKeys()) {
			s.setValue(key, markovState.getValue(key));
		}
		return s;
	}


	/**
	 * This function applies an action to the industrial benchmark
	 * @param aAction The industrial benchmark action
	 * @return The successor state
	 */
	@Override
	public double step(final DataVector aAction) {

		// apply randomSeed to PRNGs and external drivers + filter (e.g. setpoint)
		rda.reSeed(randomSeed);
		for (final ExternalDriver d : externalDrivers) {
			d.setSeed(rda.nextLong(0, Long.MAX_VALUE));
			d.filter(markovState);
		}

		// add actions to state:
		addAction((ActionDelta) aAction);

		try {
			// update spiking dynamics
			updateFatigue();

			// updated current operationalcost
			updateCurrentOperationalCost();
		} catch (final PropertiesException e) {
			e.printStackTrace();
		}

		// update convoluted operationalcosts
		updateOperationalCostCovolution();

		// update gs
		updateGS();

		updateOperationalCosts();

		// update reward
		mRewardCore.calcReward(markovState);

		// set random seed for next iteration
		randomSeed = rda.nextLong(0, Long.MAX_VALUE);
		markovState.setValue(MarkovianStateDescription.RandomSeed, Double.longBitsToDouble(randomSeed));

		//return observableState;
		return markovState.getValue(ObservableStateDescription.RewardTotal);
	}

	private void updateOperationalCosts() {

		final double rGS = markovState.getValue(MarkovianStateDescription.MisCalibration);

		// set new OperationalCosts
		final double eNewHidden = markovState.getValue(MarkovianStateDescription.OperationalCostsConv) - (crgs * (rGS - 1.0));
		final double operationalcosts = eNewHidden - rda.nextGaussian(0, 1) * (1 + 0.005 * eNewHidden);

		markovState.setValue(MarkovianStateDescription.Consumption, operationalcosts);
	}

	private void addAction(final ActionDelta aAction) {

		final double velocityMax = mMax.getValue(MarkovianStateDescription.Action_Velocity);
		final double velocityMin = mMin.getValue(MarkovianStateDescription.Action_Velocity);
		double velocity = Math.min(velocityMax, Math.max(velocityMin, markovState.getValue(MarkovianStateDescription.Action_Velocity) + aAction.getDeltaVelocity() * stepSizeVelocity));
		if (aAction instanceof ActionAbsolute) {
			final double velocityToSet = ((ActionAbsolute)aAction).getVelocity();
			double diff = velocityToSet - markovState.getValue(MarkovianStateDescription.Action_Velocity);
			if (diff > stepSizeVelocity) {
				diff = stepSizeVelocity;
			} else if (diff < -stepSizeVelocity) {
				diff = -stepSizeVelocity;
			}
			velocity = Math.min(velocityMax, Math.max(velocityMin, markovState.getValue(MarkovianStateDescription.Action_Velocity) + diff));
		}
		final double gainMax = mMax.getValue(MarkovianStateDescription.Action_Gain);
		final double gainMin = mMin.getValue(MarkovianStateDescription.Action_Gain);
		double gain = Math.min(gainMax, Math.max(gainMin, markovState.getValue(MarkovianStateDescription.Action_Gain) + aAction.getDeltaGain() * stepSizeGain));
		if (aAction instanceof ActionAbsolute) {
			final double gainToSet = ((ActionAbsolute)aAction).getGain();
			double diff = gainToSet - markovState.getValue(MarkovianStateDescription.Action_Gain);
			if (diff > stepSizeGain){
				diff = stepSizeGain;
			} else if (diff < -stepSizeGain) {
				diff = -stepSizeGain;
			}
			gain = Math.min(gainMax, Math.max(gainMin, markovState.getValue(MarkovianStateDescription.Action_Gain) + diff));
		}
		// both: 10 = 2*1.5 + 0.07*100
		final double gsScale = 2.0f*GS_BOUND + 100.0f*GS_SET_POINT_DEPENDENCY;
		double shift = (float) Math.min(100.0f, Math.max(0.0f, markovState.getValue(MarkovianStateDescription.Action_Shift) + aAction.getDeltaShift()*(MAX_REQUIRED_STEP/0.9f)*100.0f/gsScale));
		if (aAction instanceof ActionAbsolute) {
			final double shiftToSet = ((ActionAbsolute)aAction).getShift();
			double diff = shiftToSet - markovState.getValue(MarkovianStateDescription.Action_Shift);
			if (diff > ((MAX_REQUIRED_STEP/0.9f)*100.0f/gsScale)) {
				diff = ((MAX_REQUIRED_STEP/0.9f)*100.0f/gsScale);
			} else if (diff < -((MAX_REQUIRED_STEP/0.9f)*100.0f/gsScale)) {
				diff = -((MAX_REQUIRED_STEP/0.9f)*100.0f/gsScale);
			}
			shift = (float) Math.min(100.0f, Math.max(0.0f, markovState.getValue(MarkovianStateDescription.Action_Shift) + diff));
		}
		final double hiddenShift = (float) Math.min(GS_BOUND, Math.max(-GS_BOUND, (gsScale*shift/100.0f - GS_SET_POINT_DEPENDENCY*markovState.getValue(MarkovianStateDescription.SetPoint) - GS_BOUND)));

		markovState.setValue(MarkovianStateDescription.Action_Velocity, velocity);
		markovState.setValue(MarkovianStateDescription.Action_Gain, gain);
		markovState.setValue(MarkovianStateDescription.Action_Shift, shift);
		markovState.setValue(MarkovianStateDescription.EffectiveShift, hiddenShift);
	}

	/**
	 * Updates the spiking fatigue dynamics.
	 * @throws PropertiesException
	 */
	private void updateFatigue() throws PropertiesException {
		final float expLambda = 0.1f; // => scale = 1/lambda
		final float actionTolerance = 0.05f;
		final float fatigueAmplification = 1.1f;
		final float fatigueAmplificationMax = 5.0f;
		final float fatigueAmplificationStart = 1.2f;

		// action
		final double velocity = markovState.getValue(MarkovianStateDescription.Action_Velocity);
		final double gain = markovState.getValue(MarkovianStateDescription.Action_Gain);
		final double setpoint = markovState.getValue(MarkovianStateDescription.SetPoint);

		// hidden state variables for fatigue
		double hiddenStateVelocity = markovState.getValue(MarkovianStateDescription.FatigueLatent1);
		double hiddenStateGain = markovState.getValue(MarkovianStateDescription.FatigueLatent2);

		// dyn variables
		final EffectiveAction effAction = new EffectiveAction(new ActionAbsolute(velocity, gain, 0.0, this.mProperties), setpoint);
		final double effActionVelocityAlpha = effAction.getVelocityAlpha(); // => gain
		final double effActionGainBeta = effAction.getGainBeta();  // => velocity

		// base noise
		double noiseGain = 2.0 * (1.0/(1.0+Math.exp(-rda.nextExponential(expLambda))) - 0.5);
		double noiseVelocity = 2.0 * (1.0/(1.0+Math.exp(-rda.nextExponential(expLambda))) - 0.5);

		// add spikes
		// keep error within range of [0.001, 0.999] because otherwise Binomial.staticNextInt() will fail.
		noiseGain += (1 - noiseGain) * rda.nextUniform(0, 1) * rda.nextBinomial(1, Math.min(Math.max(0.001, effActionGainBeta), 0.999)) * effActionGainBeta;
		noiseVelocity += (1 - noiseVelocity) * rda.nextUniform(0, 1) * rda.nextBinomial(1, Math.min(Math.max(0.001, effActionVelocityAlpha), 0.999)) * effActionVelocityAlpha;

		// compute internal dynamics
		if (hiddenStateGain >= fatigueAmplificationStart) {
			hiddenStateGain = Math.min(fatigueAmplificationMax,  hiddenStateGain*fatigueAmplification);
		} else if (effActionGainBeta > actionTolerance) {
			hiddenStateGain = (hiddenStateGain*0.9f) + ((float)noiseGain/3.0f);
		}

		if (hiddenStateVelocity >= fatigueAmplificationStart) {
			hiddenStateVelocity = Math.min(fatigueAmplificationMax,  hiddenStateVelocity*fatigueAmplification);
		} else if (effActionVelocityAlpha > actionTolerance) {
			hiddenStateVelocity = (hiddenStateVelocity*0.9f) + ((float)noiseVelocity/3.0f);
		}

		// reset hiddenState in case actionError is within actionTolerance
		if (effActionVelocityAlpha <= actionTolerance) {
			hiddenStateVelocity = effActionVelocityAlpha;
		}
		if (effActionGainBeta <= actionTolerance) {
			hiddenStateGain = effActionGainBeta;
		}

		// compute observation variables
		double dyn;
		if (Math.max(hiddenStateVelocity, hiddenStateGain) == fatigueAmplificationMax) {
			// bad noise in case fatigueAmplificationMax is reached
			dyn = 1.0 / (1.0+Math.exp(-4.0 * rda.nextGaussian(0.6, 0.1)));
		} else {
			dyn = Math.max(noiseGain,  noiseVelocity);
		}

		final float cDGain = getConst(C.DGain);
		final float cDVelocity = getConst(C.DVelocity);
		final float cDSetPoint = getConst(C.DSetPoint);
		final float cDynBase = getConst(C.DBase);

		double dynOld = ((cDynBase / ((cDVelocity * velocity) + cDSetPoint)) - cDGain * gain*gain);
		if (dynOld < 0) {
			dynOld = 0;
		}
		dyn = ((2.f * dyn + 1.0) * dynOld) / 3.f;

		markovState.setValue(MarkovianStateDescription.Fatigue, dyn);
		markovState.setValue(MarkovianStateDescription.FatigueBase, dynOld);

		// hidden state variables for fatigue
		markovState.setValue(MarkovianStateDescription.FatigueLatent1, hiddenStateVelocity);
		markovState.setValue(MarkovianStateDescription.FatigueLatent2, hiddenStateGain);
		markovState.setValue(MarkovianStateDescription.EffectiveActionVelocityAlpha, effActionVelocityAlpha);
		markovState.setValue(MarkovianStateDescription.EffectiveActionGainBeta, effActionGainBeta);
	}

	private void updateCurrentOperationalCost() throws PropertiesException {

		final float cCostSetPoint = getConst(C.CostSetPoint);
		final float cCostGain = getConst(C.CostGain);
		final float cCostVelocity = getConst(C.CostVelocity);
		final double setpoint = markovState.getValue(MarkovianStateDescription.SetPoint);
		final double gain = markovState.getValue(MarkovianStateDescription.Action_Gain);
		final double velocity = markovState.getValue(MarkovianStateDescription.Action_Velocity);
		final double costs = cCostSetPoint * setpoint + cCostGain * gain + cCostVelocity * velocity;

		final double operationalcosts = (float) Math.exp(costs / 100.);
		markovState.setValue(MarkovianStateDescription.CurrentOperationalCost, operationalcosts);
		mOperationalCostsBuffer.add(operationalcosts);

		if (convToInit) {
			for(int i = 1; i < mOperationalCostsBuffer.size(); i++) {
				mOperationalCostsBuffer.add(operationalcosts);
			}
			convToInit = false;
		}
	}

	private void updateOperationalCostCovolution() {
		double aggregatedOperationalCosts = 0;
		int oci = 0;
		final Iterator<?> iterator = mOperationalCostsBuffer.iterator();
		while (iterator.hasNext()) {
			final double operationalCost = (Double)iterator.next();
			aggregatedOperationalCosts += mEmConvWeights[oci] * operationalCost;
			markovState.setValue("OPERATIONALCOST_" + oci, operationalCost);
			oci += 1;
		}
		markovState.setValue(MarkovianStateDescription.OperationalCostsConv, aggregatedOperationalCosts);
	}

	private void updateGS() {
		gsEnvironment.setControlPosition(markovState.getValue(MarkovianStateDescription.EffectiveShift));
		markovState.setValue(MarkovianStateDescription.MisCalibration, (float) gsEnvironment.reward());
		markovState.setValue(MarkovianStateDescription.MisCalibrationDomain, gsEnvironment.getDomain());
		markovState.setValue(MarkovianStateDescription.MisCalibrationSystemResponse, gsEnvironment.getSystemResponse());
		markovState.setValue(MarkovianStateDescription.MisCalibrationPhiIdx, gsEnvironment.getPhiIdx());
	}

	private float getConst(final C aConst) throws PropertiesException {
		return PropertiesUtil.getFloat(mProperties, aConst.name());
	}

	/**
	 * Returns the operational costs history length.
	 * The current operational costs value is part of the history.
	 * @return length of the operational-costs history (including current value)
	 */
	public int getOperationalCostsHistoryLength() {
		return mOperationalCostsBuffer.size();
	}

	/**
	 * Returns a copy of the the current <b>markovian</b> state of the dynamics.
	 *
	 * @return current internal markovian state of the industrial benchmark
	 */
	@Override
	public DataVector getMarkovState() {
		try {
			return markovState.clone();
		} catch (final CloneNotSupportedException ex) {
			// this should never happen
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Sets the current <b>markovian</b> state of the dynamics.
	 * Also, the setpoint generator is set and the operational costs
	 * are convoluted (+reward recomputed).
	 *
	 * @param markovState The markovian state from which the values are copied
	 */
	public void setMarkovState(final DataVector markovState) {

		// 1) import all key/value pairs
		for (final String key : markovState.getKeys()) {
			this.markovState.setValue(key, markovState.getValue(key));
		}

		// 2) set random number generator states
		randomSeed = Double.doubleToLongBits(this.markovState.getValue(MarkovianStateDescription.RandomSeed));

		gsEnvironment.setControlPosition(markovState.getValue(MarkovianStateDescription.EffectiveShift));
		gsEnvironment.setDomain(markovState.getValue(MarkovianStateDescription.MisCalibrationDomain));
		gsEnvironment.setSystemResponse(markovState.getValue(MarkovianStateDescription.MisCalibrationSystemResponse));
		gsEnvironment.setPhiIdx(markovState.getValue(MarkovianStateDescription.MisCalibrationPhiIdx));

		// 3) reconstruct operationalcost convolution + reward computation
		double aggregatedOperationalCosts = 0;
		for (int i = 0; i < mEmConvWeights.length; i++) {
			final String key = "OPERATIONALCOST_" + i;
			final double operationalcost = markovState.getValue(key);
			aggregatedOperationalCosts += markovState.getValue(key) * mEmConvWeights[i];
			mOperationalCostsBuffer.add(operationalcost);
		}
		markovState.setValue(MarkovianStateDescription.OperationalCostsConv, aggregatedOperationalCosts);
		//mRewardCore.setNormal(rda);
		mRewardCore.calcReward(markovState);

		// 4) set state variables to external driver (e.g. SetPointGenerator parameters)
		for (final ExternalDriver d : externalDrivers) {
			d.setConfiguration(markovState);
		}
	}

	@Override
	public void reset() {
		try {
			init();
		} catch (final PropertiesException e) {
			e.printStackTrace();
		}
	}

	@Override
	public double getReward() {
		return markovState.getValue(ObservableStateDescription.RewardTotal);
	}
}
