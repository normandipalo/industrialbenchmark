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
package com.siemens.industrialbenchmark.datavector.action;

import java.util.ArrayList;
import java.util.List;

import com.siemens.industrialbenchmark.datavector.DataVectorDescriptionImpl;

/**
 * Action description for the ActionDelta.
 *
 * @author Michel Tokic
 */
public class ActionDeltaDescription extends DataVectorDescriptionImpl {

	public static final String DeltaVelocity = "DeltaVelocity"; // Velocity
	public static final String DeltaGain = "DeltaGain"; // Gain
	public static final String DeltaShift = "DeltaShift"; // GS

	private static final List<String> ACTION_VARS = new ArrayList<String>();

	static {
		ACTION_VARS.add(DeltaVelocity);
		ACTION_VARS.add(DeltaGain);
		ACTION_VARS.add(DeltaShift);
	}

	public ActionDeltaDescription() {
		super(ACTION_VARS);
	}
}

