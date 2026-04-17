const refreshIntervalMs = 5000;

function byId(id) {
  return document.getElementById(id);
}

function clearNode(node) {
  while (node.firstChild) {
    node.removeChild(node.firstChild);
  }
}

function make(tag, className, text) {
  const el = document.createElement(tag);
  if (className) {
    el.className = className;
  }
  if (text !== undefined && text !== null) {
    el.textContent = String(text);
  }
  return el;
}

async function loadJson(path) {
  const response = await fetch(path);
  if (!response.ok) {
    throw new Error(`${path} failed: ${response.status}`);
  }
  return response.json();
}

async function loadDashboardData() {
  const [health, overview] = await Promise.all([
    loadJson("/actuator/health"),
    loadJson("/ops/overview"),
  ]);
  return { health, overview };
}

function renderHealth(health) {
  const container = document.querySelector("#health-card .card-body");
  clearNode(container);

  const details = health.components?.cdc?.details ?? {};
  const pill = make("span", `pill${health.status === "UP" ? "" : " warn"}`, health.status);
  container.appendChild(pill);

  const grid = make("div", "metric-grid");
  const values = [
    ["Connector", details.connectorName ?? "n/a"],
    ["Runtime", details.runtimeEnabled ? "enabled" : "disabled"],
    ["Lifecycle", details.lifecycleRunning ? "running" : "stopped"],
    ["Startup", details.startupStrategy ?? "n/a"],
    ["Running Rebuilds", details.runningRebuildCount ?? 0],
    ["Failed Rebuilds", details.failedRebuildCount ?? 0],
  ];

  values.forEach(([label, value]) => {
    const card = make("div", "metric");
    card.appendChild(make("span", "metric-label", label));
    card.appendChild(make("span", "metric-value", value));
    grid.appendChild(card);
  });

  container.appendChild(grid);
}

function renderCheckpoint(checkpoint) {
  const container = document.querySelector("#checkpoint-card .card-body");
  clearNode(container);

  const primary = make("div", "stack-item");
  primary.appendChild(make("strong", null, checkpoint.connectorName ?? "unknown connector"));
  primary.appendChild(make("small", null, checkpoint.present
    ? `${checkpoint.binlogFilename}:${checkpoint.binlogPosition}`
    : "No stored checkpoint"));
  container.appendChild(primary);
}

function renderSummary(summary) {
  const container = document.querySelector("#task-card .card-body");
  clearNode(container);
  const grid = make("div", "metric-grid");
  const items = [
    ["Pending", summary.pending],
    ["Running", summary.running],
    ["Done", summary.done],
    ["Failed", summary.failed],
    ["Obsolete", summary.obsolete],
  ];
  items.forEach(([label, value]) => {
    const metric = make("div", "metric");
    metric.appendChild(make("span", "metric-label", label));
    metric.appendChild(make("span", "metric-value", value ?? 0));
    grid.appendChild(metric);
  });
  container.appendChild(grid);
}

function renderFullSyncTasks(tasks) {
  const container = document.querySelector("#full-sync-card .card-body");
  clearNode(container);
  const stack = make("div", "stack");
  if (!tasks || tasks.length === 0) {
    stack.appendChild(make("div", "stack-item", "No recent full sync tasks."));
  } else {
    tasks.forEach((task) => {
      const row = make("div", "stack-item");
      row.appendChild(make("strong", null, `${task.databaseName}.${task.tableName}`));
      row.appendChild(make("small", null, `${task.status} @ ${task.cutoverBinlogFilename}:${task.cutoverBinlogPosition}`));
      stack.appendChild(row);
    });
  }
  container.appendChild(stack);
}

function renderSchemaStates(states) {
  const container = document.querySelector("#schema-card .card-body");
  clearNode(container);
  const stack = make("div", "stack");
  if (!states || states.length === 0) {
    stack.appendChild(make("div", "stack-item", "No schema state rows."));
  } else {
    states.forEach((state) => {
      const row = make("div", "stack-item");
      row.appendChild(make("strong", null, `${state.databaseName}.${state.tableName}`));
      row.appendChild(make("small", null, `${state.status} | ${state.ddlType ?? "n/a"}`));
      stack.appendChild(row);
    });
  }
  container.appendChild(stack);
}

function renderRecentEvents(events) {
  const container = document.querySelector("#events-card .card-body");
  clearNode(container);
  if (!events || events.length === 0) {
    container.appendChild(make("div", "event-row", "No recent events."));
    return;
  }
  events.forEach((event) => {
    const row = make("div", "event-row");
    row.appendChild(make("strong", null, `${event.eventType} | ${event.result}`));
    row.appendChild(make("div", "mono", `${event.reference ?? "-"} | ${event.timestamp}`));
    row.appendChild(make("small", null, event.message ?? ""));
    container.appendChild(row);
  });
}

function renderOverview(data) {
  renderHealth(data.health);
  renderCheckpoint(data.overview.checkpoint);
  renderSummary(data.overview.rebuildSummary);
  renderFullSyncTasks(data.overview.fullSyncTasks);
  renderSchemaStates(data.overview.schemaStates);
  renderRecentEvents(data.overview.recentEvents);
  byId("last-refresh").textContent = `Last refresh: ${new Date().toLocaleString()}`;
}

function setError(message) {
  const banner = byId("error-banner");
  if (!message) {
    banner.textContent = "";
    banner.classList.add("hidden");
    return;
  }
  banner.textContent = message;
  banner.classList.remove("hidden");
}

async function refresh() {
  try {
    const data = await loadDashboardData();
    renderOverview(data);
    setError("");
  } catch (error) {
    setError(error.message);
  }
}

byId("refresh-button").addEventListener("click", () => {
  refresh();
});

refresh();
setInterval(refresh, refreshIntervalMs);
