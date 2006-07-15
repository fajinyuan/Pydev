package org.python.pydev.editor.codecompletion.revisited;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.python.pydev.core.FindInfo;
import org.python.pydev.core.FullRepIterable;
import org.python.pydev.core.ICodeCompletionASTManager;
import org.python.pydev.core.ICompletionRequest;
import org.python.pydev.core.ICompletionState;
import org.python.pydev.core.IModule;
import org.python.pydev.core.IModulesManager;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.IToken;
import org.python.pydev.core.ModulesKey;
import org.python.pydev.core.REF;
import org.python.pydev.core.Tuple;
import org.python.pydev.core.log.Log;
import org.python.pydev.editor.actions.PyAction;
import org.python.pydev.editor.codecompletion.CompletionRequest;
import org.python.pydev.editor.codecompletion.PyCodeCompletion;
import org.python.pydev.editor.codecompletion.revisited.modules.AbstractModule;
import org.python.pydev.editor.codecompletion.revisited.modules.SourceModule;
import org.python.pydev.editor.codecompletion.revisited.visitors.Definition;
import org.python.pydev.parser.PyParser;
import org.python.pydev.parser.jython.SimpleNode;
import org.python.pydev.parser.jython.ast.FunctionDef;
import org.python.pydev.parser.visitors.NodeUtils;

public abstract class AbstractASTManager implements ICodeCompletionASTManager, Serializable {

	public AbstractASTManager(){
	}
	
    /**
     * This is the guy that will handle project things for us
     */
    public IModulesManager modulesManager;
    public IModulesManager getModulesManager(){
        return modulesManager;
    }

    


    /**
     * Set the nature this ast manager works with (if no project is available and a nature is).
     */
    public void setNature(IPythonNature nature){
        getModulesManager().setPythonNature(nature);
    }
    
    public IPythonNature getNature() {
        return getModulesManager().getNature();
    }
    
    
	public abstract void setProject(IProject project, boolean restoreDeltas) ;

	public abstract void rebuildModule(File file, IDocument doc, IProject project, IProgressMonitor monitor, IPythonNature nature) ;

	public abstract void removeModule(File file, IProject project, IProgressMonitor monitor) ;
	

    /**
     * Returns the imports that start with a given string. The comparisson is not case dependent. Passes all the modules in the cache.
     * 
     * @param original is the name of the import module eg. 'from toimport import ' would mean that the original is 'toimport'
     * or something like 'foo.bar' or an empty string (if only 'import').
     * @return a Set with the imports as tuples with the name, the docstring.
     */
    public IToken[] getCompletionsForImport(final String original, ICompletionRequest r) {
        CompletionRequest request = (CompletionRequest) r;
        IPythonNature nature = request.nature;
        
        String relative = null;
        if(request.editorFile != null){
            String moduleName = nature.getAstManager().getModulesManager().resolveModule(REF.getFileAbsolutePath(request.editorFile));
            if(moduleName != null){
                String tail = FullRepIterable.headAndTail(moduleName)[0];
                if(original.length() > 0){
                    relative = tail+"."+original;
                }else{
                    relative = tail;
                }
            }
        }
        
        String absoluteModule = original;
        if (absoluteModule.endsWith(".")) {
            absoluteModule = absoluteModule.substring(0, absoluteModule.length() - 1);
        }
        //absoluteModule = absoluteModule.toLowerCase().trim();

        //set to hold the completion (no duplicates allowed).
        Set<IToken> set = new HashSet<IToken>();

        //first we get the imports... that complete for the token.
        getAbsoluteImportTokens(absoluteModule, set, PyCodeCompletion.TYPE_IMPORT, false);

        //Now, if we have an initial module, we have to get the completions
        //for it.
        getTokensForModule(original, nature, absoluteModule, set);

        if(relative != null && relative.equals(absoluteModule) == false){
            getAbsoluteImportTokens(relative, set, PyCodeCompletion.TYPE_RELATIVE_IMPORT, false);
            getTokensForModule(relative, nature, relative, set);
        }
        return (IToken[]) set.toArray(new IToken[0]);
    }

    
    
    /**
     * @param moduleToGetTokensFrom the string that represents the token from where we are getting the imports
     * @param set the set where the tokens should be added
     */
    protected void getAbsoluteImportTokens(String moduleToGetTokensFrom, Set<IToken> set, int type, boolean onlyFilesOnSameLevel) {
    	SortedMap<ModulesKey,ModulesKey> modulesStartingWith = modulesManager.getAllModulesStartingWith(moduleToGetTokensFrom);
    	Iterator<ModulesKey> itModules = modulesStartingWith.keySet().iterator();
    	while(itModules.hasNext()){
    		ModulesKey key = itModules.next();
    		
            String element = key.name;
//			if (element.startsWith(moduleToGetTokensFrom)) { we don't check that anymore because we get all the modules starting with it already
                if(onlyFilesOnSameLevel && key.file != null && key.file.isDirectory()){
                	continue; // we only want those that are in the same directory, and not in other directories...
                }
                element = element.substring(moduleToGetTokensFrom.length());
                
                //we just want those that are direct
                //this means that if we had initially element = testlib.unittest.anothertest
                //and element became later = .unittest.anothertest, it will be ignored (we
                //should only analyze it if it was something as testlib.unittest and became .unittest
                //we only check this if we only want file modules (in
                if(onlyFilesOnSameLevel && PyAction.countChars('.', element) > 1){
                	continue;
                }

                boolean goForIt = false;
                //if initial is not empty only get those that start with a dot (submodules, not
                //modules that start with the same name).
                //e.g. we want xml.dom
                //and not xmlrpclib
                //if we have xml token (not using the qualifier here) 
                if (moduleToGetTokensFrom.length() != 0) {
                    if (element.length() > 0 && element.charAt(0) == ('.')) {
                        element = element.substring(1);
                        goForIt = true;
                    }
                } else {
                    goForIt = true;
                }

                if (element.length() > 0 && goForIt) {
                    String[] splitted = FullRepIterable.dotSplit(element);
                    if (splitted.length > 0) {
                        //this is the completion
                        set.add(new ConcreteToken(splitted[0], "", "", moduleToGetTokensFrom, type));
                    }
                }
//            }
        }
    }

    /**
     * @param original this is the initial module where the completion should happen (may have class in it too)
     * @param moduleToGetTokensFrom
     * @param set set where the tokens should be added
     */
    protected void getTokensForModule(String original, IPythonNature nature, String moduleToGetTokensFrom, Set<IToken> set) {
        if (moduleToGetTokensFrom.length() > 0) {
            if (original.endsWith(".")) {
                original = original.substring(0, original.length() - 1);
            }

            Tuple<IModule, String> modTok = findModuleFromPath(original, nature, false, null); //the current module name is not used as it is not relative
            IModule m = modTok.o1;
            String tok = modTok.o2;
            
            if(m == null){
            	//we were unable to find it with the given path, so, there's nothing else to do here...
            	return;
            }
            
            IToken[] globalTokens;
            if(tok != null && tok.length() > 0){
                CompletionState state2 = new CompletionState(-1,-1,tok,nature);
                state2.builtinsGotten = true; //we don't want to get builtins here
                globalTokens = m.getGlobalTokens(state2, this);
            }else{
                CompletionState state2 = new CompletionState(-1,-1,"",nature);
                state2.builtinsGotten = true; //we don't want to get builtins here
                globalTokens = getCompletionsForModule(m, state2);
            }
            
            for (int i = 0; i < globalTokens.length; i++) {
                IToken element = globalTokens[i];
                //this is the completion
                set.add(element);
            }
        }
    }



    /**
     * @param file
     * @param doc
     * @param state
     * @return
     */
    public static IModule createModule(File file, IDocument doc, ICompletionState state, ICodeCompletionASTManager manager) {
    	IPythonNature pythonNature = state.getNature();
    	int line = state.getLine();
    	IModulesManager projModulesManager = manager.getModulesManager();

    	return AbstractModule.createModuleFromDoc(file, doc, pythonNature, line, projModulesManager);
    }

    /** 
     * @see org.python.pydev.core.ICodeCompletionASTManager#getCompletionsForToken(java.io.File, org.eclipse.jface.text.IDocument, org.python.pydev.editor.codecompletion.revisited.CompletionState)
     */
    public IToken[] getCompletionsForToken(File file, IDocument doc, ICompletionState state) {
        IModule module = createModule(file, doc, state, this);
        return getCompletionsForModule(module, state);
    }

	/** 
     * @see org.python.pydev.editor.codecompletion.revisited.ICodeCompletionASTManage#getCompletionsForToken(org.eclipse.jface.text.IDocument, org.python.pydev.editor.codecompletion.revisited.CompletionState)
     */
    public IToken[] getCompletionsForToken(IDocument doc, ICompletionState state) {
        IToken[] completionsForModule;
        try {
            Tuple<SimpleNode, Throwable> obj = PyParser.reparseDocument(new PyParser.ParserInfo(doc, true, state.getNature(), state.getLine()));
	        SimpleNode n = obj.o1;
	        IModule module = AbstractModule.createModule(n);
        
            completionsForModule = getCompletionsForModule(module, state);

        } catch (CompletionRecursionException e) {
            completionsForModule = new IToken[]{ new ConcreteToken(e.getMessage(), e.getMessage(), "","", PyCodeCompletion.TYPE_UNKNOWN)};
        }
        
        return completionsForModule;
    }

    
    
    /**
     * By default does not look for relative import
     */
    public IModule getModule(String name, IPythonNature nature, boolean dontSearchInit) {
    	return modulesManager.getModule(name, nature, dontSearchInit, false);
    }

    /**
     * This method returns the module that corresponds to the path passed as a parameter.
     * 
     * @param name
     * @param lookingForRelative determines whether we're looking for a relative module (in which case we should
     * not check in other places... only in the module)
     * @return the module represented by this name
     */
    public IModule getModule(String name, IPythonNature nature, boolean dontSearchInit, boolean lookingForRelative) {
    	if(lookingForRelative){
    		return modulesManager.getRelativeModule(name, nature);
    	}else{
    		return modulesManager.getModule(name, nature, dontSearchInit);
    	}
    }

    /**
     * Identifies the token passed and if it maps to a builtin not 'easily recognizable', as
     * a string or list, we return it.
     * 
     * @param state
     * @return
     */
    protected IToken[] getBuiltinsCompletions(ICompletionState state){
        ICompletionState state2 = state.getCopy();

        //check for the builtin types.
        state2.setActivationToken (NodeUtils.getBuiltinType(state.getActivationToken()));

        if(state2.getActivationToken() != null){
            IModule m = getBuiltinMod(state.getNature());
            return m.getGlobalTokens(state2, this);
        }
        return null;
    }

    /** 
     * @see org.python.pydev.editor.codecompletion.revisited.ICodeCompletionASTManage#getCompletionsForModule(org.python.pydev.editor.codecompletion.revisited.modules.AbstractModule, org.python.pydev.editor.codecompletion.revisited.CompletionState)
     */
    public IToken[] getCompletionsForModule(IModule module, ICompletionState state) {
    	return getCompletionsForModule(module, state, true);
    }
    
    /** 
     * @see org.python.pydev.editor.codecompletion.revisited.ICodeCompletionASTManage#getCompletionsForModule(org.python.pydev.editor.codecompletion.revisited.modules.AbstractModule, org.python.pydev.editor.codecompletion.revisited.CompletionState, boolean)
     */
    public IToken[] getCompletionsForModule(IModule module, ICompletionState state, boolean searchSameLevelMods) {
        if(PyCodeCompletion.DEBUG_CODE_COMPLETION){
            Log.toLogFile(this, "getCompletionsForModule");
        }
        ArrayList<IToken> importedModules = new ArrayList<IToken>();
        if(state.getLocalImportsGotten() == false){
            //in the first analyzed module, we have to get the local imports too. 
            state.setLocalImportsGotten (true);
            if(module != null){
                importedModules.addAll(module.getLocalImportedModules(state.getLine(), state.getCol()));
            }
        }

        IToken[] builtinsCompletions = getBuiltinsCompletions(state);
        if(builtinsCompletions != null){
            return builtinsCompletions;
        }
        
        String act = state.getActivationToken();
        int parI = act.indexOf('(');
        if(parI != -1){
            state.setActivationToken(act.substring(0, parI));
        }
        	
        if (module != null) {

            //get the tokens (global, imported and wild imported)
            IToken[] globalTokens = module.getGlobalTokens();
            importedModules.addAll(Arrays.asList(module.getTokenImportedModules()));
            IToken[] wildImportedModules = module.getWildImportedModules();
            
            
	        //now, lets check if this is actually a module that is an __init__ (if so, we have to get all
	        //the other .py files as modules that are in the same level as the __init__)
            Set<IToken> initial = new HashSet<IToken>();
            if(searchSameLevelMods){
		        String modName = module.getName();
		        if(modName != null && modName.endsWith(".__init__")){
		        	HashSet<IToken> gotten = new HashSet<IToken>();
					getAbsoluteImportTokens(FullRepIterable.getParentModule(modName), gotten, PyCodeCompletion.TYPE_IMPORT, true);
					for (IToken token : gotten) {
						if(token.getRepresentation().equals("__init__") == false){
							initial.add(token);
						}
					}
		        }
            }

	        if (state.getActivationToken().length() == 0) {

		        List<IToken> completions = getGlobalCompletions(globalTokens, importedModules.toArray(new IToken[0]), wildImportedModules, state, module);
		        
		        //now find the locals for the module
		        if (state.getLine() >= 0){
		            IToken[] localTokens = module.getLocalTokens(state.getLine(), state.getCol());
		            for (int i = 0; i < localTokens.length; i++) {
                        completions.add(localTokens[i]); 
                    }
		        }
		        completions.addAll(initial); //just addd all that are in the same level if it was an __init__

                return completions.toArray(new IToken[0]);
                
            }else{ //ok, we have a token, find it and get its completions.
                
                //first check if the token is a module... if it is, get the completions for that module.
                IToken[] t = findTokensOnImportedMods(importedModules.toArray(new IToken[0]), state, module);
                if(t != null && t.length > 0){
                    return t;
                }
                
                //if it is an __init__, modules on the same level are treated as local tokens
                if(searchSameLevelMods){
	                t = searchOnSameLevelMods(initial, state);
	                if(t != null && t.length > 0){
	                	return t;
	                }
                }

                //wild imports: recursively go and get those completions and see if any matches it.
                for (int i = 0; i < wildImportedModules.length; i++) {

                    IToken name = wildImportedModules[i];
                    IModule mod = getModule(name.getAsRelativeImport(module.getName()), state.getNature(), false); //relative (for wild imports this is ok... only a module can be used in wild imports)
                    
                    if (mod == null) {
                        mod = getModule(name.getOriginalRep(), state.getNature(), false); //absolute
                    }
                    
                    
                    if (mod != null) {
                        IToken[] completionsForModule = getCompletionsForModule(mod, state);
                        if(completionsForModule.length > 0)
                            return completionsForModule;
                    } else {
                        //"Module not found:" + name.getRepresentation()
                    }
                }

                //it was not a module (would have returned already), so, try to get the completions for a global token defined.
                IToken[] tokens = null;
                tokens = module.getGlobalTokens(state, this);
                if (tokens.length > 0){
                    return tokens;
                }
                
                //If it was still not found, go to builtins.
                IModule builtinsMod = getBuiltinMod(state.getNature());
                if(builtinsMod != null && builtinsMod != module){
	                tokens = getCompletionsForModule( builtinsMod, state);
	                if (tokens.length > 0){
	                    if (tokens[0].getRepresentation().equals("ERROR:") == false){
	                        return tokens;
	                    }
	                }
                }
                
                return getAssignCompletions( module, state);
            }

            
        }else{
            System.err.println("Module passed in is null!!");
        }
        
        return new IToken[0];
    }

    /**
     * Attempt to search on modules on the same level as this one (this will only happen if we are in an __init__
     * module (otherwise, the initial set will be empty)
     * 
     * @param initial this is the set of tokens generated from modules in the same level
     * @param state the current state of the completion
     * 
     * @return a list of tokens found.
     */
    protected IToken[] searchOnSameLevelMods(Set<IToken> initial, ICompletionState state) {
        for (IToken token : initial) {
			//ok, maybe it was from the set that is in the same level as this one (this will only happen if we are on an __init__ module)
        	String rep = token.getRepresentation();
        	
			if(state.getActivationToken().startsWith(rep)){
				String absoluteImport = token.getAsAbsoluteImport();
				IModule sameLevelMod = getModule(absoluteImport, state.getNature(), true);
				if(sameLevelMod == null){
					return null;
				}
				
				String qualifier = state.getActivationToken().substring(rep.length());

				ICompletionState copy = state.getCopy();
				copy.setBuiltinsGotten (true); //we don't want builtins... 

				if(state.getActivationToken().equals(rep)){
					copy.setActivationToken ("");
					return getCompletionsForModule(sameLevelMod, copy);
					
				} else if(qualifier.startsWith(".")){
					copy.setActivationToken (qualifier.substring(1));
					return getCompletionsForModule(sameLevelMod, copy);
        		}
        	}
		}
        return null;
	}

	/**
     * If we got here, either there really is no definition from the token
     * or it is not looking for a definition. This means that probably
     * it is something like.
     * 
     * It also can happen in many scopes, so, first we have to check the current
     * scope and then pass to higher scopes
     * 
     * e.g. foo = Foo()
     *      foo. | Ctrl+Space
     * 
     * so, first thing is discovering in which scope we are (Storing previous scopes so 
     * that we can search in other scopes as well).
     * 
     * 
     * @param activationToken
     * @param qualifier
     * @param module
     * @param line
     * @param col
     * @return
     */
    public IToken[] getAssignCompletions( IModule module, ICompletionState state) {
        if (module instanceof SourceModule) {
            SourceModule s = (SourceModule) module;
            try {
                Definition[] defs = s.findDefinition(state.getActivationToken(), state.getLine(), state.getCol(), state.getNature(), new ArrayList<FindInfo>());
                for (int i = 0; i < defs.length; i++) {
                    if(!(defs[i].ast instanceof FunctionDef)){
                        //we might want to extend that later to check the return of some function...
                                
	                    ICompletionState copy = state.getCopy();
	                    copy.setActivationToken (defs[i].value);
	                    copy.setLine(defs[i].line);
	                    copy.setCol(defs[i].col);
	                    module = defs[i].module;

	                    state.checkDefinitionMemory(module, defs[i]);
	                            
	                    IToken[] tks = getCompletionsForModule(module, copy);
	                    if(tks.length > 0)
	                        return tks;
                    }
                }
                
                
            } catch (CompletionRecursionException e) {
                //thats ok
            } catch (Exception e) {
                throw new RuntimeException(e);
            } catch (Throwable t) {
                throw new RuntimeException("A throwable exception has been detected "+t.getClass());
            }
        }
        return new IToken[0];
    }

    /** 
     * @see org.python.pydev.editor.codecompletion.revisited.ICodeCompletionASTManage#getGlobalCompletions
     */
    public List getGlobalCompletions(IToken[] globalTokens, IToken[] importedModules, IToken[] wildImportedModules, ICompletionState state, IModule current) {
        if(PyCodeCompletion.DEBUG_CODE_COMPLETION){
            Log.toLogFile(this, "getGlobalCompletions");
        }
        List<IToken> completions = new ArrayList<IToken>();

        //in completion with nothing, just go for what is imported and global tokens.
        for (int i = 0; i < globalTokens.length; i++) {
            completions.add(globalTokens[i]);
        }

        //now go for the token imports
        for (int i = 0; i < importedModules.length; i++) {
            completions.add(importedModules[i]);
        }

        //wild imports: recursively go and get those completions.
        for (int i = 0; i < wildImportedModules.length; i++) {

            IToken name = wildImportedModules[i];
            getCompletionsForWildImport(state, current, completions, name);
        }

        if(!state.getBuiltinsGotten()){
            state.setBuiltinsGotten (true) ;
            if(PyCodeCompletion.DEBUG_CODE_COMPLETION){
                Log.toLogFile(this, "getBuiltinCompletions");
            }
            //last thing: get completions from module __builtin__
            getBuiltinCompletions(state, completions);
            if(PyCodeCompletion.DEBUG_CODE_COMPLETION){
                Log.toLogFile(this, "END getBuiltinCompletions");
            }
        }
        return completions;
    }

    /**
     * @return the builtin completions
     */
    public List<IToken> getBuiltinCompletions(ICompletionState state, List<IToken> completions) {
        IPythonNature nature = state.getNature();
        IToken[] builtinCompletions = getBuiltinComps(nature);
        if(builtinCompletions != null){
			for (int i = 0; i < builtinCompletions.length; i++) {
				completions.add(builtinCompletions[i]);
			}
        }
        return completions;
        
    }


    /**
     * @return the tokens in the builtins
     */
	protected IToken[] getBuiltinComps(IPythonNature nature) {
		IToken[] builtinCompletions = nature.getBuiltinCompletions();
		
        if(builtinCompletions == null || builtinCompletions.length == 0){
        	IModule builtMod = getBuiltinMod(nature);
        	if(builtMod != null){
        		builtinCompletions = builtMod.getGlobalTokens();
        		nature.setBuiltinCompletions(builtinCompletions);
        	}
        }
		return builtinCompletions;
	}

	/**
	 * @return the module that represents the builtins
	 */
	protected IModule getBuiltinMod(IPythonNature nature) {
		IModule mod = nature.getBuiltinMod();
		if(mod == null){
			mod = getModule("__builtin__", nature, false);
			nature.setBuiltinMod(mod);
		}
		return mod;
	}

    /**
     * @see org.python.pydev.editor.codecompletion.revisited.ICodeCompletionASTManage#getCompletionsForWildImport
     */
    public List getCompletionsForWildImport(ICompletionState state, IModule current, List completions, IToken name) {
        try {
        	//this one is an exception... even though we are getting the name as a relative import, we say it
        	//is not because we want to get the module considering __init__
        	IModule mod = null;
        	
        	if(current != null){
        		//we cannot get the relative path if we don't have a current module
        		mod = getModule(name.getAsRelativeImport(current.getName()), state.getNature(), false);
        	}

            if (mod == null) {
                mod = getModule(name.getOriginalRep(), state.getNature(), false); //absolute import
            }

            if (mod != null) {
                state.checkWildImportInMemory(current, mod);
                IToken[] completionsForModule = getCompletionsForModule(mod, state);
                for (int j = 0; j < completionsForModule.length; j++) {
                    completions.add(completionsForModule[j]);
                }
            } else {
                //"Module not found:" + name.getRepresentation()
            }
        } catch (CompletionRecursionException e) {
            //probably found a recursion... let's return the tokens we have so far
        }
        return completions;
    }

    public IToken[] findTokensOnImportedMods( IToken[] importedModules, ICompletionState state, IModule current) {
        Tuple<IModule, String> o = findOnImportedMods(importedModules, state.getNature(), state.getActivationToken(), current.getName());
        
        if(o == null)
            return null;
        
        IModule mod = o.o1;
        String tok = o.o2;

        if(tok.length() == 0){
            //the activation token corresponds to an imported module. We have to get its global tokens and return them.
            ICompletionState copy = state.getCopy();
            copy.setActivationToken("");
            copy.setBuiltinsGotten(true); //we don't want builtins... 
            return getCompletionsForModule(mod, copy);
        }else if (mod != null){
            ICompletionState copy = state.getCopy();
            copy.setActivationToken(tok);
            copy.setCol(-1);
            copy.setLine(-1);
            copy.raiseNFindTokensOnImportedModsCalled(mod, tok);
            
            return getCompletionsForModule(mod, copy);
        }
        return null;
    }

    /**
     * @param activationToken
     * @param importedModules
     * @param module
     * @return tuple with:
     * 0: mod
     * 1: tok
     */
    public Tuple<IModule, String> findOnImportedMods( IPythonNature nature, String activationToken, IModule current) {
        IToken[] importedModules = current.getTokenImportedModules();
        return findOnImportedMods(importedModules, nature, activationToken, current.getName());
    }
    
    /**
     * This function tries to find some activation token defined in some imported module.  
     * @return tuple with: the module and the token that should be used from it.
     * 
     * @param this is the activation token we have. It may be a single token or some dotted name.
     * 
     * If it is a dotted name, such as testcase.TestCase, we need to match against some import
     * represented as testcase or testcase.TestCase.
     * 
     * If a testcase.TestCase matches against some import named testcase, the import is returned and
     * the TestCase is put as the module
     * 
     * 0: mod
     * 1: tok
     */
    public Tuple<IModule, String> findOnImportedMods( IToken[] importedModules, IPythonNature nature, String activationToken, String currentModuleName) {
        	
    	FullRepIterable iterable = new FullRepIterable(activationToken, true);
    	for(String tok : iterable){
    		for (IToken importedModule : importedModules) {
        	
	            final String modRep = importedModule.getRepresentation(); //this is its 'real' representation (alias) on the file (if it is from xxx import a as yyy, it is yyy)
	            
	            if(modRep.equals(tok)){
                    String act = activationToken;
	            	return findOnImportedMods(importedModule, tok, nature, act, currentModuleName);
	            }
	        }
        }   
        return null;
    }

    
    /**
     * Checks if some module can be resolved and returns the module it is resolved to (and to which token).
     * 
     */
    protected Tuple<IModule, String> findOnImportedMods(IToken importedModule, String tok, IPythonNature nature, 
    		String activationToken, String currentModuleName) {
    	
    	Tuple<IModule, String> modTok = null;
    	IModule mod = null;
        
        //check as relative with complete rep
        String asRelativeImport = importedModule.getAsRelativeImport(currentModuleName);
		modTok = findModuleFromPath(asRelativeImport, nature, true, currentModuleName);
        mod = modTok.o1;
        if(checkValidity(currentModuleName, mod)){
            Tuple<IModule, String> ret = fixTok(modTok, tok, activationToken);
            return ret;
        }
        

        
        //check if the import actually represents some token in an __init__ file
        String originalWithoutRep = importedModule.getOriginalWithoutRep();
        if(!originalWithoutRep.endsWith("__init__")){
        	originalWithoutRep = originalWithoutRep + ".__init__";
        }
		modTok = findModuleFromPath(originalWithoutRep, nature, true, null);
        mod = modTok.o1;
        if(modTok.o2.endsWith("__init__") == false && checkValidity(currentModuleName, mod)){
        	if(mod.isInGlobalTokens(importedModule.getRepresentation(), nature, false)){
        		//then this is the token we're looking for (otherwise, it might be a module).
        		Tuple<IModule, String> ret =  fixTok(modTok, tok, activationToken);
        		if(ret.o2.length() == 0){
        			ret.o2 = importedModule.getRepresentation();
        		}else{
        			ret.o2 = importedModule.getRepresentation()+"."+ret.o2;
        		}
        		return ret;
        	}
        }
        

        
        //the most 'simple' case: check as absolute with original rep
        modTok = findModuleFromPath(importedModule.getOriginalRep(), nature, false, null);
        mod = modTok.o1;
        if(checkValidity(currentModuleName, mod)){
            Tuple<IModule, String> ret =  fixTok(modTok, tok, activationToken);
            return ret;
        }
        
        
        
        

        
        //ok, one last shot, to see a relative looking in folders __init__
        modTok = findModuleFromPath(asRelativeImport, nature, false, null);
        mod = modTok.o1;
        if(checkValidity(currentModuleName, mod)){
            Tuple<IModule, String> ret = fixTok(modTok, tok, activationToken);
            //now let's see if what we did when we found it as a relative import is correct:
            
            //if we didn't find it in an __init__ module, all should be ok
            if(!mod.getName().endsWith("__init__")){
                return ret;
            }
            //otherwise, we have to be more cautious...
            //if the activation token is empty, then it is the module we were looking for
            //if it is not the initial token we were looking for, it is correct
            //if it is in the global tokens of the found module it is correct
            //if none of this situations was found, we probably just found the same token we had when we started (unless I'm mistaken...)
            else if(activationToken.length() == 0 || ret.o2.equals(activationToken) == false || mod.isInGlobalTokens(activationToken, nature, false)){
                return ret;
            }
        }
        
        return null;    	
    }

    
    
    
    
	protected boolean checkValidity(String currentModuleName, IModule mod) {
		if(mod == null){
			return false;
		}
		
		String modName = mod.getName();
		if(modName == null){
			return true;
		}
		
		//still in the same module
		if(modName.equals(currentModuleName)){
			return false;
		}
		
//		if(currentModuleName != null && modName.endsWith(".__init__")){
//			//we have to check it without the __init__
//			String withoutLastPart = FullRepIterable.getWithoutLastPart(modName);
//			if(withoutLastPart.equals(currentModuleName)){
//				return false;
//			}
//		}
		return true;
	}
            
            
    /**
     * Fixes the token if we found a module that was just a substring from the initial activation token.
     * 
     * This means that if we had testcase.TestCase and found it as TestCase, the token is added with TestCase
     */
    protected Tuple<IModule, String> fixTok(Tuple<IModule, String> modTok, String tok, String activationToken) {
    	if(activationToken.length() > tok.length() && activationToken.startsWith(tok)){
    		String toAdd = activationToken.substring(tok.length() + 1);
    		if(modTok.o2.length() == 0){
    			modTok.o2 = toAdd;
    		}else{
    			modTok.o2 += "."+toAdd;
    		}
    	}
		return modTok;
	}


    /**
     * This function receives a path (rep) and extracts a module from that path.
     * First it tries with the full path, and them removes a part of the final of
     * that path until it finds the module or the path is empty.
     * 
     * @param currentModuleName this is the module name (used to check validity for relative imports) -- not used if dontSearchInit is false
     * if this parameter is not null, it means we're looking for a relative import. When checking for relative imports, 
     * we should only check the modules that are directly under this project (so, we should not check the whole pythonpath for
     * it, just direct modules) 
     * 
     * @return tuple with found module and the String removed from the path in
     * order to find the module.
     */
    protected Tuple<IModule, String> findModuleFromPath(String rep, IPythonNature nature, boolean dontSearchInit, String currentModuleName){
        String tok = "";
        boolean lookingForRelative = currentModuleName != null;
		IModule mod = getModule(rep, nature, dontSearchInit, lookingForRelative);
        String mRep = rep;
        int index;
        while(mod == null && (index = mRep.lastIndexOf('.')) != -1){
            tok = mRep.substring(index+1) + "."+tok;
            mRep = mRep.substring(0,index);
            mod = getModule(mRep, nature, dontSearchInit, lookingForRelative);
        }
        if (tok.endsWith(".")){
            tok = tok.substring(0, tok.length()-1); //remove last point if found.
        }
        
        if(dontSearchInit && currentModuleName != null && mod != null){
        	String parentModule = FullRepIterable.getParentModule(currentModuleName);
        	//if we are looking for some relative import token, it can only match if the name found is not less than the parent
        	//of the current module because of the way in that relative imports are meant to be written.
        	
        	//if it equal, it should not match either, as it was found as the parent module... this can not happen because it must find
        	//it with __init__ if it was the parent module
        	if (mod.getName().length() <= parentModule.length()){
        		return new Tuple<IModule, String>(null, null);
        	}
        }
        return new Tuple<IModule, String>((AbstractModule)mod, tok);
    }
}
