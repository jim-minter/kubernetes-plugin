package org.csanchez.jenkins.plugins.kubernetes;

import jenkins.security.MasterToSlaveCallable;

public class Killer extends MasterToSlaveCallable<Void, Exception> {
  private static final long serialVersionUID = 4586604371440872161L;

  public Void call() {
    System.exit(0);
    return null;
  }
}
