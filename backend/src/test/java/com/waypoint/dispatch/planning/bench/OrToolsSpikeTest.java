package com.waypoint.dispatch.planning.bench;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import org.junit.jupiter.api.Test;

class OrToolsSpikeTest {
  @Test
  void nativeSolversLoad() {
    Loader.loadNativeLibraries();
    for (String id : new String[] {"HIGHS", "SCIP", "CBC", "GLOP"}) {
      MPSolver s = MPSolver.createSolver(id);
      System.out.println("MPSolver " + id + ": " + (s == null ? "missing" : "ok"));
    }
    CpModel m = new CpModel();
    m.newBoolVar("x");
    assertNotNull(new CpSolver().solve(m));
  }
}
