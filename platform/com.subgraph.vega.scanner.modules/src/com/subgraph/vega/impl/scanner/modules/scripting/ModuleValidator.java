package com.subgraph.vega.impl.scanner.modules.scripting;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import com.subgraph.vega.api.scanner.modules.ModuleScriptType;

public class ModuleValidator {
	
	private final Scriptable moduleScope;
	private String moduleName;
	private ModuleScriptType moduleType;
	private Function runFunction;
	private boolean isValidated;
	private boolean isDisabled;
	
	public ModuleValidator(Scriptable moduleScope) {
		this.moduleScope = moduleScope;
	}
	
	public static class ModuleValidationException extends Exception {
		private static final long serialVersionUID = 1L;
		ModuleValidationException(String message) {
			super(message);
		}
	}
	
	public void validate() throws ModuleValidationException {
		if(isValidated)
			return;
		final Scriptable moduleObject = getModule();
		moduleName = getStringFromModuleObject(moduleObject, "name");
		moduleType = getScriptType(moduleObject);
		runFunction = getGlobalFunction("run");
		isDisabled = getFlagFromModuleObject(moduleObject, "disabled");
		isValidated = true;
	}
	
	public String getName() {
		if(!isValidated)
			throw new IllegalStateException("Cannot get name because module is not validated");
		return moduleName;
	}
	
	public boolean isDisabled() {
		if(!isValidated)
			throw new IllegalStateException("Cannot get disabled flag because module is not validated");
		return isDisabled;
	}
	
	public ModuleScriptType getType() {
		if(!isValidated)
			throw new IllegalStateException("Cannot get type because module is not validated");
		return moduleType;
	}
	
	public Function getRunFunction() {
		if(!isValidated)
			throw new IllegalStateException("Cannot get run function because module is not validated");
		return runFunction;
	}
	
	private Scriptable getModule() throws ModuleValidationException {
		final Object ob = moduleScope.get("module", moduleScope);
		if(ob == Scriptable.NOT_FOUND) 
			throw new ModuleValidationException("No 'module' object found.");
		return Context.toObject(ob, moduleScope);
	}
	
	private ModuleScriptType getScriptType(Scriptable module) throws ModuleValidationException {
		final String typeName = getStringFromModuleObject(module, "type");
		final ModuleScriptType type = ModuleScriptType.lookup(typeName);
		if(type == null) 
			throw new ModuleValidationException("Unrecognized module type: "+ typeName);
		else
			return type;
	}
	
	private String getStringFromModuleObject(Scriptable module, String name) throws ModuleValidationException {
		final Object ob = module.get(name, moduleScope);
		if(ob == Scriptable.NOT_FOUND) 
			throw new ModuleValidationException("Could not find module property '"+ name +"'.");
		if(!(ob instanceof String)) 
			throw new ModuleValidationException("Module property '"+ name +"' is not a string type as expected.");
		return (String) ob;
	}
	
	private boolean getFlagFromModuleObject(Scriptable module, String name) {
		final Object ob = module.get(name, moduleScope);
		return !(ob == Scriptable.NOT_FOUND);
	}
	
	private Function getGlobalFunction(String name) throws ModuleValidationException {
		final Object ob = moduleScope.get(name, moduleScope);
		if(ob == Scriptable.NOT_FOUND)
			throw new ModuleValidationException("Could not find global function '"+ name +"()'.");
		if(!(ob instanceof Function)) 
			throw new ModuleValidationException("Global identifier '" + name +"' is not a function as expected");
		return (Function) ob;
	}
	
	
}
