package edu.columbia.cs.og.structure.impl;

import java.util.HashMap;

public class FeaturesDictionary {
	HashMap<String,String> features = new HashMap<String,String>();
	
	public String getFeature(String result){
		String r = features.get(result);
		if(r==null){
			features.put(result, result);
			r=result;
		}
		return r;
	}
}
