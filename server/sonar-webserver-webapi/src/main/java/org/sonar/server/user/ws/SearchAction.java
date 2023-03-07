/*
 * SonarQube
 * Copyright (C) 2009-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.user.ws;

import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.server.ws.Change;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.Paging;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.user.UserDto;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.es.SearchResult;
import org.sonar.server.issue.AvatarResolver;
import org.sonar.server.management.ManagedInstanceService;
import org.sonar.server.user.UserSession;
import org.sonar.server.user.index.UserDoc;
import org.sonar.server.user.index.UserIndex;
import org.sonar.server.user.index.UserQuery;
import org.sonarqube.ws.Users;
import org.sonarqube.ws.Users.SearchWsResponse;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.emptyToNull;
import static java.lang.Boolean.TRUE;
import static java.util.Optional.ofNullable;
import static org.sonar.api.server.ws.WebService.Param.PAGE;
import static org.sonar.api.server.ws.WebService.Param.PAGE_SIZE;
import static org.sonar.api.server.ws.WebService.Param.TEXT_QUERY;
import static org.sonar.api.utils.DateUtils.formatDateTime;
import static org.sonar.api.utils.Paging.forPageIndex;
import static org.sonar.core.util.stream.MoreCollectors.toList;
import static org.sonar.server.ws.WsUtils.writeProtobuf;
import static org.sonarqube.ws.Users.SearchWsResponse.Groups;
import static org.sonarqube.ws.Users.SearchWsResponse.ScmAccounts;
import static org.sonarqube.ws.Users.SearchWsResponse.User;
import static org.sonarqube.ws.Users.SearchWsResponse.newBuilder;

public class SearchAction implements UsersWsAction {
  private static final String DEACTIVATED_PARAM = "deactivated";
  private static final int MAX_PAGE_SIZE = 500;

  private final UserSession userSession;
  private final UserIndex userIndex;
  private final DbClient dbClient;
  private final AvatarResolver avatarResolver;
  private final ManagedInstanceService managedInstanceService;

  public SearchAction(UserSession userSession, UserIndex userIndex, DbClient dbClient, AvatarResolver avatarResolver,
    ManagedInstanceService managedInstanceService) {
    this.userSession = userSession;
    this.userIndex = userIndex;
    this.dbClient = dbClient;
    this.avatarResolver = avatarResolver;
    this.managedInstanceService = managedInstanceService;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller.createAction("search")
      .setDescription("Get a list of users. By default, only active users are returned.<br/>" +
        "The following fields are only returned when user has Administer System permission or for logged-in in user :" +
        "<ul>" +
        "   <li>'email'</li>" +
        "   <li>'externalIdentity'</li>" +
        "   <li>'externalProvider'</li>" +
        "   <li>'groups'</li>" +
        "   <li>'lastConnectionDate'</li>" +
        "   <li>'tokensCount'</li>" +
        "</ul>" +
        "Field 'lastConnectionDate' is only updated every hour, so it may not be accurate, for instance when a user authenticates many times in less than one hour.")
      .setSince("3.6")
      .setChangelog(
        new Change("10.0", "Response includes 'managed' field."),
        new Change("9.7", "New parameter 'deactivated' to optionally search for deactivated users"),
        new Change("7.7", "New field 'lastConnectionDate' is added to response"),
        new Change("7.4", "External identity is only returned to system administrators"),
        new Change("6.4", "Paging response fields moved to a Paging object"),
        new Change("6.4", "Avatar has been added to the response"),
        new Change("6.4", "Email is only returned when user has Administer System permission"))
      .setHandler(this)
      .setResponseExample(getClass().getResource("search-example.json"));

    action.addPagingParams(50, SearchOptions.MAX_PAGE_SIZE);

    action.createParam(TEXT_QUERY)
      .setMinimumLength(2)
      .setDescription("Filter on login, name and email.<br />" +
        "This parameter can either be case sensitive and perform an exact match, or case insensitive and perform a partial match (contains), depending on the scenario:<br />" +
        "<ul>" +
        "  <li>" +
        "    If the search query is <em>less or equal to 15 characters</em>, then the query is <em>case insensitive</em>, and will match any login, name, or email, that " +
        "    <em>contains</em> the search query." +
        "  </li>" +
        "  <li>" +
        "    If the search query is <em>greater than 15 characters</em>, then the query becomes <em>case sensitive</em>, and will match any login, name, or email, that " +
        "    <em>exactly matches</em> the search query." +
        "  </li>" +
        "</ul>");
    action.createParam(DEACTIVATED_PARAM)
      .setSince("9.7")
      .setDescription("Return deactivated users instead of active users")
      .setRequired(false)
      .setDefaultValue(false)
      .setBooleanPossibleValues();
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    Users.SearchWsResponse wsResponse = doHandle(toSearchRequest(request));
    writeProtobuf(wsResponse, request, response);
  }

  private Users.SearchWsResponse doHandle(SearchRequest request) {
    SearchOptions options = new SearchOptions().setPage(request.getPage(), request.getPageSize());
    SearchResult<UserDoc> result = userIndex.search(UserQuery.builder().setActive(!request.isDeactivated()).setTextQuery(request.getQuery()).build(), options);
    try (DbSession dbSession = dbClient.openSession(false)) {
      List<String> logins = result.getDocs().stream().map(UserDoc::login).collect(toList());
      Multimap<String, String> groupsByLogin = dbClient.groupMembershipDao().selectGroupsByLogins(dbSession, logins);
      List<UserDto> users = dbClient.userDao().selectByOrderedLogins(dbSession, logins);
      Map<String, Integer> tokenCountsByLogin = dbClient.userTokenDao().countTokensByUsers(dbSession, users);
      Map<String, Boolean> userUuidToIsManaged = managedInstanceService.getUserUuidToManaged(dbSession, getUserUuids(users));
      Paging paging = forPageIndex(request.getPage()).withPageSize(request.getPageSize()).andTotal((int) result.getTotal());
      return buildResponse(users, groupsByLogin, tokenCountsByLogin, userUuidToIsManaged, paging);
    }
  }

  private static Set<String> getUserUuids(List<UserDto> users) {
    return users.stream().map(UserDto::getUuid).collect(Collectors.toSet());
  }

  private SearchWsResponse buildResponse(List<UserDto> users, Multimap<String, String> groupsByLogin, Map<String, Integer> tokenCountsByLogin,
    Map<String, Boolean> userUuidToIsManaged, Paging paging) {
    SearchWsResponse.Builder responseBuilder = newBuilder();
    users.forEach(user -> responseBuilder.addUsers(
      towsUser(user, firstNonNull(tokenCountsByLogin.get(user.getUuid()), 0), groupsByLogin.get(user.getLogin()), userUuidToIsManaged.get(user.getUuid()))
    ));
    responseBuilder.getPagingBuilder()
      .setPageIndex(paging.pageIndex())
      .setPageSize(paging.pageSize())
      .setTotal(paging.total())
      .build();
    return responseBuilder.build();
  }

  private User towsUser(UserDto user, @Nullable Integer tokensCount, Collection<String> groups, Boolean isManaged) {
    User.Builder userBuilder = User.newBuilder().setLogin(user.getLogin());
    ofNullable(user.getName()).ifPresent(userBuilder::setName);
    if (userSession.isLoggedIn()) {
      ofNullable(emptyToNull(user.getEmail())).ifPresent(u -> userBuilder.setAvatar(avatarResolver.create(user)));
      userBuilder.setActive(user.isActive());
      userBuilder.setLocal(user.isLocal());
      ofNullable(user.getExternalIdentityProvider()).ifPresent(userBuilder::setExternalProvider);
      if (!user.getScmAccountsAsList().isEmpty()) {
        userBuilder.setScmAccounts(ScmAccounts.newBuilder().addAllScmAccounts(user.getScmAccountsAsList()));
      }
    }
    if (userSession.isSystemAdministrator() || Objects.equals(userSession.getUuid(), user.getUuid())) {
      ofNullable(user.getEmail()).ifPresent(userBuilder::setEmail);
      if (!groups.isEmpty()) {
        userBuilder.setGroups(Groups.newBuilder().addAllGroups(groups));
      }
      ofNullable(user.getExternalLogin()).ifPresent(userBuilder::setExternalIdentity);
      ofNullable(tokensCount).ifPresent(userBuilder::setTokensCount);
      ofNullable(user.getLastConnectionDate()).ifPresent(date -> userBuilder.setLastConnectionDate(formatDateTime(date)));
      userBuilder.setManaged(TRUE.equals(isManaged));
    }
    return userBuilder.build();
  }

  private static SearchRequest toSearchRequest(Request request) {
    int pageSize = request.mandatoryParamAsInt(PAGE_SIZE);
    checkArgument(pageSize <= MAX_PAGE_SIZE, "The '%s' parameter must be less than %s", PAGE_SIZE, MAX_PAGE_SIZE);
    return SearchRequest.builder()
      .setQuery(request.param(TEXT_QUERY))
      .setDeactivated(request.mandatoryParamAsBoolean(DEACTIVATED_PARAM))
      .setPage(request.mandatoryParamAsInt(PAGE))
      .setPageSize(pageSize)
      .build();
  }

  private static class SearchRequest {
    private final Integer page;
    private final Integer pageSize;
    private final String query;
    private final boolean deactivated;

    private SearchRequest(Builder builder) {
      this.page = builder.page;
      this.pageSize = builder.pageSize;
      this.query = builder.query;
      this.deactivated = builder.deactivated;
    }

    @CheckForNull
    public Integer getPage() {
      return page;
    }

    @CheckForNull
    public Integer getPageSize() {
      return pageSize;
    }

    @CheckForNull
    public String getQuery() {
      return query;
    }

    public boolean isDeactivated() {
      return deactivated;
    }

    public static Builder builder() {
      return new Builder();
    }
  }

  private static class Builder {
    private Integer page;
    private Integer pageSize;
    private String query;
    private boolean deactivated;

    private Builder() {
      // enforce factory method use
    }

    public Builder setPage(@Nullable Integer page) {
      this.page = page;
      return this;
    }

    public Builder setPageSize(@Nullable Integer pageSize) {
      this.pageSize = pageSize;
      return this;
    }

    public Builder setQuery(@Nullable String query) {
      this.query = query;
      return this;
    }

    public Builder setDeactivated(boolean deactivated) {
      this.deactivated = deactivated;
      return this;
    }

    public SearchRequest build() {
      return new SearchRequest(this);
    }
  }
}
