package com.waypoint.dispatch.issues.contract;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueView;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.shared.domain.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The only way another module reads issues. */
public interface IssueQuery {

  /** Open and assigned issues for a depot, most severe first. */
  Page<IssueView> openIssues(String depotCode, Optional<String> cursor, int limit);

  List<IssueView> issuesFor(SubjectRef subject);

  /** One issue the ambient actor can see, by id: Messaging reads a report's wording and who raised it (issue #136). */
  Optional<IssueView> issue(UUID issueId);

  /**
   * FF-09: the questions asked of a trip's stores that day, newest first, read as
   * the asking person (row-level security decides the rows). For the trip's
   * problem room in Intelligence (decision suggestions part 3).
   */
  java.util.List<com.waypoint.dispatch.issues.contract.IssueViews.DecisionView> decisionsOfTrip(
      UUID tripId, java.time.LocalDate serviceDate);
}
