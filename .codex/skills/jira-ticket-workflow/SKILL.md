---
name: jira-ticket-workflow
description: Create or reference Jira tickets for the DeployDock repository and its DEP project only when the user explicitly instructs Codex to do so. Use for explicit requests to create, find, or link DeployDock Jira work. Never create tickets, search Jira, or change ticket status implicitly.
---

# DeployDock Jira Workflow

Use Jira project `DeployDock` (`DEP`).

## Non-negotiable rules

1. Create a Jira ticket only when the user explicitly instructs you to create one. Do not treat an implementation request, repository instruction, branch, commit, or pull request as permission to create a ticket.
2. Never change a Jira ticket's status. Do not call a transition operation for any reason. Reading and reporting the current status is allowed.
3. Never search Jira unless the user explicitly requests a search or duplicate check. A ticket creation request alone does not authorize a search. Do not proactively list projects or issues; use the fixed `DEP` project mapping.
4. Never create a ticket until the user has explicitly provided both the problem and the completion criteria. If either is missing, ask for the missing information and wait. Do not infer these two fields from repository context.
5. Name every new branch associated with Jira work exactly `feat/DEP-<ISSUE_NUMBER>`. If the issue number was not provided or returned by a ticket created in the current turn, ask for it. Never search for or infer the number. Do not rename a published branch unless the user explicitly requests it.

## Creation criteria

When explicitly instructed to create a ticket:

- Confirm that the user supplied both `Problem` and `Completion criteria`. Make no Jira call until both are present.
- Follow the user's requested scope and fields exactly.
- Inspect repository evidence only after the required inputs are present and only to clarify implementation scope.
- Create the ticket directly in `DEP` without searching Jira unless the user also requests a search or duplicate check.
- Keep unrelated changes and inferred future work out of the ticket.

Do not create a ticket for questions, read-only investigation, reviews, status checks, or implementation work unless the user explicitly asks for ticket creation.

## Ticket format

- Use `Task` by default and `Bug` only for a confirmed defect. Use another type only when the user requests it or the scope clearly requires it.
- Assign the current Atlassian user unless another assignee is named.
- Keep the ticket concise: summary, problem, scope, and verifiable completion criteria.
- Do not invent estimates, priority, labels, releases, or requirements.
- Add commits, pull requests, comments, or links to Jira only when explicitly requested.
