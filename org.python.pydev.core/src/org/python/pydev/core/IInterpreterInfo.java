/*
 * Created on Feb 21, 2006
 */
package org.python.pydev.core;

public interface IInterpreterInfo {
    
    /**
     * @return a String such as 2.5 or 2.4 representing the python version that created this interpreter. 
     */
    public String getVersion();

}
