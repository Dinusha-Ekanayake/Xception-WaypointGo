package com.waypoint.dispatch.issues.contract;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueView;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.shared.domain.Page;
import java.util.List;
import java.util.Optional;

/** The only way another module reads issues. */
public interface IssueQuery {

  /** Open and assigned issues for a depot, most severe first. */
  Page<IssueView> openIssues(String depotCode, Optional<String> cursor, int limit);

  List<IssueView> issuesFor(SubjectRef subject);
}
