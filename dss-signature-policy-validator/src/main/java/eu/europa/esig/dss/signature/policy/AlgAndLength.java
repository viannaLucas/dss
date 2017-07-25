package eu.europa.esig.dss.signature.policy;

import java.util.List;

public interface AlgAndLength {

	String getAlgID();

	Integer getMinKeyLength();

	List<SignPolExtn> getSignPolExtensions();

}