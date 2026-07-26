import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

const DASHBOARD_HTML = new URL("../../main/resources/static/index.html", import.meta.url);

class FakeClassList {
  constructor(owner) {
    this.owner = owner;
    this.values = new Set();
  }

  replaceFrom(value) {
    this.values = new Set(String(value || "").split(/\s+/).filter(Boolean));
    this.sync();
  }

  add(...values) {
    values.forEach(value => this.values.add(value));
    this.sync();
  }

  remove(...values) {
    values.forEach(value => this.values.delete(value));
    this.sync();
  }

  toggle(value, force) {
    const enabled = force === undefined ? !this.values.has(value) : Boolean(force);
    if (enabled) this.values.add(value);
    else this.values.delete(value);
    this.sync();
    return enabled;
  }

  contains(value) {
    return this.values.has(value);
  }

  sync() {
    this.owner._className = [...this.values].join(" ");
  }
}

class FakeElement {
  constructor(tagName, ownerDocument) {
    this.tagName = String(tagName).toUpperCase();
    this.ownerDocument = ownerDocument;
    this.parentNode = null;
    this.children = [];
    this.listeners = new Map();
    this.attributes = new Map();
    this.style = {};
    this.classList = new FakeClassList(this);
    this._className = "";
    this._id = "";
    this._innerHTML = "";
    this._textContent = "";
    this.value = "";
    this.defaultValue = "";
    this.checked = false;
    this.defaultChecked = false;
    this.disabled = false;
    this.hidden = false;
    this.open = false;
    this.files = [];
    this.options = [];
    this.elements = Object.create(null);
    this.clientWidth = 460;
  }

  get id() {
    return this._id;
  }

  set id(value) {
    this._id = String(value || "");
    if (this._id) this.ownerDocument.register(this);
  }

  get className() {
    return this._className;
  }

  set className(value) {
    this.classList.replaceFrom(value);
  }

  get innerHTML() {
    return this._innerHTML;
  }

  set innerHTML(value) {
    this._innerHTML = String(value);
    this._textContent = "";
    this.children = [];
    this.ownerDocument.parseFragment(this, this._innerHTML);
  }

  get textContent() {
    if (this._textContent) return this._textContent;
    return this.children.map(child => child.textContent).join("") || stripMarkup(this._innerHTML);
  }

  set textContent(value) {
    this._textContent = String(value ?? "");
    this._innerHTML = "";
    this.children = [];
  }

  get selectedIndex() {
    return this.options.findIndex(option => option.value === this.value);
  }

  setAttribute(name, value) {
    const stringValue = String(value);
    this.attributes.set(name, stringValue);
    if (name === "id") this.id = stringValue;
    if (name === "class") this.className = stringValue;
    if (name === "hidden") this.hidden = true;
    if (name === "disabled") this.disabled = true;
  }

  getAttribute(name) {
    if (name === "id") return this.id || null;
    if (name === "class") return this.className || null;
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  removeAttribute(name) {
    this.attributes.delete(name);
    if (name === "hidden") this.hidden = false;
    if (name === "disabled") this.disabled = false;
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  remove() {
    if (!this.parentNode) return;
    this.parentNode.children = this.parentNode.children.filter(child => child !== this);
    this.parentNode = null;
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  removeEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    this.listeners.set(type, listeners.filter(candidate => candidate !== listener));
  }

  async emit(type, overrides = {}) {
    const event = {
      type,
      target: this,
      currentTarget: this,
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
      ...overrides
    };
    for (const listener of [...(this.listeners.get(type) || [])]) {
      await listener(event);
    }
    return event;
  }

  click() {
    void this.emit("click");
  }

  focus() {}

  showModal() {
    this.open = true;
  }

  close() {
    this.open = false;
  }

  reportValidity() {
    return true;
  }

  reset() {
    for (const control of Object.values(this.elements)) {
      control.value = control.defaultValue;
      control.checked = control.defaultChecked;
    }
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  querySelectorAll(selector) {
    const matches = [];
    const visit = node => {
      for (const child of node.children) {
        if (matchesSelector(child, selector)) matches.push(child);
        visit(child);
      }
    };
    visit(this);
    return matches;
  }

  getBoundingClientRect() {
    return { width: 160, height: 60 };
  }
}

class FakeDocument {
  constructor() {
    this.byId = new Map();
    this.documentElement = new FakeElement("html", this);
    this.body = new FakeElement("body", this);
    this.documentElement.appendChild(this.body);
  }

  register(element) {
    this.byId.set(element.id, element);
  }

  getElementById(id) {
    return this.byId.get(id) || null;
  }

  createElement(tagName) {
    return new FakeElement(tagName, this);
  }

  createElementNS(_namespace, tagName) {
    return this.createElement(tagName);
  }

  querySelector(selector) {
    return this.body.querySelector(selector);
  }

  parseFragment(parent, html) {
    const formMatch = html.match(/<form\b([^>]*)>([\s\S]*?)<\/form>/i);
    if (formMatch) {
      const attributes = parseAttributes(formMatch[1]);
      const form = this.createElement("form");
      applyAttributes(form, attributes);
      parent.appendChild(form);
      this.parseForm(form, formMatch[2]);
      return;
    }

    const idPattern = /<(button|div|span|p)\b([^>]*\bid="[^"]+"[^>]*)>/gi;
    for (const match of html.matchAll(idPattern)) {
      const element = this.createElement(match[1]);
      applyAttributes(element, parseAttributes(match[2]));
      parent.appendChild(element);
    }
  }

  parseForm(form, html) {
    const addControl = (tagName, rawAttributes, body = "") => {
      const attributes = parseAttributes(rawAttributes);
      const control = this.createElement(tagName);
      applyAttributes(control, attributes);
      control.name = attributes.name || "";

      if (tagName === "select") {
        control.options = [...body.matchAll(/<option\b([^>]*)>([\s\S]*?)<\/option>/gi)]
          .map(optionMatch => {
            const optionAttributes = parseAttributes(optionMatch[1]);
            return {
              value: decodeHtml(optionAttributes.value || ""),
              textContent: stripMarkup(optionMatch[2]),
              selected: Object.hasOwn(optionAttributes, "selected")
            };
          });
        const selected = control.options.find(option => option.selected) || control.options[0];
        control.value = selected ? selected.value : "";
      } else if (tagName === "textarea") {
        control.value = decodeHtml(body);
      } else {
        control.value = decodeHtml(attributes.value || "");
        control.checked = Object.hasOwn(attributes, "checked");
        control.defaultChecked = control.checked;
        control.disabled = Object.hasOwn(attributes, "disabled");
      }
      control.defaultValue = control.value;
      if (control.name) form.elements[control.name] = control;
      form.appendChild(control);
      return control;
    };

    for (const match of html.matchAll(/<input\b([^>]*)>/gi)) addControl("input", match[1]);
    for (const match of html.matchAll(/<select\b([^>]*)>([\s\S]*?)<\/select>/gi)) {
      addControl("select", match[1], match[2]);
    }
    for (const match of html.matchAll(/<textarea\b([^>]*)>([\s\S]*?)<\/textarea>/gi)) {
      addControl("textarea", match[1], match[2]);
    }

    const statusMatch = html.match(/<div\b([^>]*\bid="diaryStatus"[^>]*)>([\s\S]*?)<\/div>/i);
    if (statusMatch) {
      const status = this.createElement("div");
      applyAttributes(status, parseAttributes(statusMatch[1]));
      status.textContent = stripMarkup(statusMatch[2]);
      form.appendChild(status);
    }

    const buttonMatch = html.match(/<button\b([^>]*\bclass="[^"]*diary-save[^"]*"[^>]*)>([\s\S]*?)<\/button>/i);
    if (buttonMatch) {
      const button = this.createElement("button");
      applyAttributes(button, parseAttributes(buttonMatch[1]));
      button.textContent = stripMarkup(buttonMatch[2]);
      form.appendChild(button);
    }
  }
}

function matchesSelector(element, selector) {
  if (selector.startsWith("#")) return element.id === selector.slice(1);
  if (selector.startsWith(".")) return element.classList.contains(selector.slice(1));
  const [tagName, className] = selector.split(".");
  return element.tagName === tagName.toUpperCase() &&
    (!className || element.classList.contains(className));
}

function parseAttributes(source) {
  const attributes = Object.create(null);
  const pattern = /([:\w-]+)(?:\s*=\s*"([^"]*)")?/g;
  for (const match of source.matchAll(pattern)) {
    attributes[match[1]] = match[2] === undefined ? "" : decodeHtml(match[2]);
  }
  return attributes;
}

function applyAttributes(element, attributes) {
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value);
  if (attributes.value !== undefined) element.value = attributes.value;
  if (Object.hasOwn(attributes, "disabled")) element.disabled = true;
  if (Object.hasOwn(attributes, "hidden")) element.hidden = true;
}

function decodeHtml(value) {
  return String(value)
    .replaceAll("&quot;", '"')
    .replaceAll("&#39;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&");
}

function stripMarkup(value) {
  return decodeHtml(String(value).replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim());
}

function addStaticElement(document, parent, tagName, id, options = {}) {
  const element = document.createElement(tagName);
  element.id = id;
  if (options.className) element.className = options.className;
  if (options.hidden) element.hidden = true;
  parent.appendChild(element);
  return element;
}

function selectOptions(values) {
  return values.map(([value, textContent]) => ({ value, textContent, selected: false }));
}

function createStaticDocument() {
  const document = new FakeDocument();
  const body = document.body;

  addStaticElement(document, body, "span", "subtitle");
  addStaticElement(document, body, "button", "downloadArchive");
  addStaticElement(document, body, "button", "downloadCsv");
  addStaticElement(document, body, "button", "restoreArchive");
  addStaticElement(document, body, "button", "themeToggle");
  addStaticElement(document, body, "div", "transferStatus");
  addStaticElement(document, body, "input", "archiveFile");

  const filterPanel = addStaticElement(document, body, "section", "dashboardFilters");
  const filterForm = addStaticElement(document, filterPanel, "form", "dashboardFilterForm");
  const activity = addStaticElement(document, filterForm, "select", "filterActivity");
  activity.options = selectOptions([
    ["", "All activities"], ["Unspecified", "Not reported"], ["SinglesMatch", "Singles match"],
    ["DoublesMatch", "Doubles match"], ["ConditionedGame", "Conditioned game"],
    ["Drill", "Drill"], ["Shadow", "Shadow"], ["FreePlay", "Free play"],
    ["Conditioning", "Conditioning"]
  ]);
  const completion = addStaticElement(document, filterForm, "select", "filterCompletion");
  completion.options = selectOptions([
    ["", "Any completion"], ["Unreported", "Not reported"],
    ["Completed", "Completed"], ["StoppedEarly", "Stopped early"]
  ]);
  const quality = addStaticElement(document, filterForm, "select", "filterQuality");
  quality.options = selectOptions([
    ["", "Usable recordings"], ["all", "All, including unusable"],
    ["Unreviewed", "Not reviewed"], ["Complete", "Complete recording"],
    ["Partial", "Partial recording"], ["Unusable", "Unusable · audit only"]
  ]);
  const tag = addStaticElement(document, filterForm, "input", "filterTag");
  const filterStatus = addStaticElement(document, filterForm, "div", "filterStatus");
  const reset = addStaticElement(document, filterForm, "button", "resetFilters");
  const apply = addStaticElement(document, filterForm, "button", "applyFilters");
  for (const element of [activity, completion, quality, tag]) {
    element.defaultValue = "";
  }
  Object.assign(filterForm.elements, { activityMode: activity, completion, recordingQuality: quality, comparisonTag: tag });
  void filterStatus;
  void reset;
  void apply;

  addStaticElement(document, body, "div", "content");
  addStaticElement(document, body, "div", "tooltip");

  const dialog = addStaticElement(document, body, "dialog", "authDialog");
  const authForm = addStaticElement(document, dialog, "form", "authForm");
  const authToken = addStaticElement(document, authForm, "input", "authToken");
  const authError = addStaticElement(document, authForm, "p", "authError", { hidden: true });
  const authCancel = addStaticElement(document, authForm, "button", "authCancel");
  authForm.elements.authToken = authToken;
  void authError;
  void authCancel;

  return document;
}

class FakeStorage {
  constructor() {
    this.values = new Map();
  }

  getItem(key) {
    return this.values.has(key) ? this.values.get(key) : null;
  }

  setItem(key, value) {
    this.values.set(key, String(value));
  }

  removeItem(key) {
    this.values.delete(key);
  }
}

function extractDashboardScript() {
  const html = readFileSync(DASHBOARD_HTML, "utf8");
  const match = html.match(/<script>([\s\S]*?)<\/script>/i);
  assert.ok(match, "dashboard inline script exists");
  return match[1];
}

function createHarness({
  search = "",
  hash = "",
  bootstrapFetch = () => new Promise(() => {}),
  recordBootstrapFetches = false
} = {}) {
  const document = createStaticDocument();
  const location = { pathname: "/", search, hash };
  const storage = new FakeStorage();
  const windowListeners = new Map();
  const state = {
    recordFetches: recordBootstrapFetches,
    fetches: [],
    fetchImpl: bootstrapFetch,
    confirmResult: true
  };

  const window = {
    document,
    location,
    sessionStorage: storage,
    innerWidth: 1280,
    innerHeight: 900,
    addEventListener(type, listener) {
      const listeners = windowListeners.get(type) || [];
      listeners.push(listener);
      windowListeners.set(type, listeners);
    },
    matchMedia() { return { matches: false }; },
    confirm() { return state.confirmResult; }
  };
  window.window = window;
  window.history = {
    replaceState(_state, _title, next) {
      const parsed = new URL(next, "http://badwatch.test");
      location.pathname = parsed.pathname;
      location.search = parsed.search;
      location.hash = parsed.hash;
    }
  };

  const fetch = async (resource, init = {}) => {
    if (state.recordFetches) state.fetches.push({ resource: String(resource), init });
    return state.fetchImpl(String(resource), init);
  };
  const context = vm.createContext({
    window,
    document,
    location,
    sessionStorage: storage,
    fetch,
    Headers,
    URL,
    URLSearchParams,
    Blob,
    Response,
    console,
    setTimeout,
    clearTimeout,
    requestAnimationFrame() { return 1; }
  });

  const hooks = `
    ;globalThis.__dashboardTest = {
      apiFetch, apiJson, dashboardResource, dashboardSearchParams,
      hydrateDashboardFilters, refreshDashboardFilters,
      renderSessionDetail, renderSession, buildDiaryEditor,
      reloadDiaryConflict, route, sessionCache
    };
  `;
  vm.runInContext(extractDashboardScript() + hooks, context, {
    filename: "server/src/main/resources/static/index.html"
  });
  if (!recordBootstrapFetches) state.fetches = [];
  state.recordFetches = true;

  return {
    context,
    document,
    window,
    location,
    storage,
    state,
    hooks: context.__dashboardTest,
    setFetch(handler) {
      state.fetchImpl = handler;
      state.fetches = [];
    }
  };
}

function jsonResponse(status, body, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers }
  });
}

function emptyDashboard(marker = null) {
  return { sessionCount: 0, sessions: [], marker };
}

function detailEnvelope({ revision = 1, notes = "Original note", detectedHits = 2 } = {}) {
  const startedAtMillis = 1_700_000_000_000;
  const summary = {
    durationMillis: 120_000,
    totalShots: detectedHits,
    shotCounts: {},
    averageHeartRate: 0,
    maxHeartRate: 0,
    heartRateSampleCount: 0,
    heartRateCoverage: 0,
    cardiovascularLoad: null
  };
  const session = {
    id: "session-1",
    startedAtMillis,
    endedAtMillis: startedAtMillis + summary.durationMillis,
    shots: [],
    processAbsenceGaps: [],
    summary
  };
  return {
    raw: {
      session,
      rallyProfile: { rallies: [], totalWorkMillis: 0 },
      corrections: { hitRevisions: [], trimRevisions: [] },
      context: {
        activityMode: "Drill",
        comparisonTag: "rear court",
        completion: "Completed",
        recordingQuality: "Complete",
        diaryReviewStatus: "Reviewed",
        equipment: {},
        conditions: {}
      },
      report: { rpe: 6, soreness: [], sorenessReviewed: true, notes },
      diaryRevision: revision,
      deviceId: "watch-device",
      appVersion: "0.3.0"
    },
    reviewed: {
      session: { ...session, summary: { ...summary } },
      rallyProfile: { rallies: [], totalWorkMillis: 0 },
      effectiveMetrics: {
        window: { trimFromStartMillis: 0, trimFromEndMillis: 0 },
        trimExcludedDetectedHitCount: 0,
        falseHitCount: 0,
        reportedMissedHitCount: 0,
        correctedDetectedHitCount: detectedHits,
        effectiveHitCount: detectedHits,
        unknownFalseHitIds: [],
        hasCorrections: false
      },
      processAbsenceCount: 0,
      unobservedMillis: 0,
      observedMillis: summary.durationMillis,
      insights: []
    }
  };
}

async function waitFor(predicate, message = "condition") {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (predicate()) return;
    await new Promise(resolve => setImmediate(resolve));
  }
  assert.fail(`Timed out waiting for ${message}`);
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

test("protected API rejects a stale token, prompts once, and retries with the replacement", async () => {
  const harness = createHarness();
  harness.storage.setItem("badwatch.bearerToken", "stale-token");
  harness.setFetch((_resource, init) => {
    const token = new Headers(init.headers).get("Authorization");
    return token === "Bearer current-token"
      ? jsonResponse(200, { unlocked: true })
      : jsonResponse(401, { error: "Unauthorized" });
  });

  const resultPromise = harness.hooks.apiJson("api/v1/dashboard");
  await waitFor(() => harness.document.getElementById("authDialog").open, "token prompt");
  assert.equal(harness.document.getElementById("authError").hidden, false);
  harness.document.getElementById("authToken").value = "current-token";
  await harness.document.getElementById("authForm").emit("submit");

  assert.deepEqual(await resultPromise, { unlocked: true });
  assert.equal(harness.state.fetches.length, 2);
  assert.equal(new Headers(harness.state.fetches[0].init.headers).get("Authorization"), "Bearer stale-token");
  assert.equal(new Headers(harness.state.fetches[1].init.headers).get("Authorization"), "Bearer current-token");
  assert.equal(harness.storage.getItem("badwatch.bearerToken"), "current-token");
  assert.equal(harness.document.getElementById("authDialog").open, false);
});

test("filters hydrate from the URL and apply, error, and reset without losing the last good view", async () => {
  const harness = createHarness({
    search: "?activityMode=Drill&completion=Completed&recordingQuality=Partial&comparisonTag=league"
  });
  const { document, location } = harness;

  assert.equal(document.getElementById("filterActivity").value, "Drill");
  assert.equal(document.getElementById("filterCompletion").value, "Completed");
  assert.equal(document.getElementById("filterQuality").value, "Partial");
  assert.equal(document.getElementById("filterTag").value, "league");

  harness.setFetch(() => jsonResponse(200, emptyDashboard("filtered")));
  await document.getElementById("dashboardFilterForm").emit("submit");
  await waitFor(() => harness.window.__data?.marker === "filtered", "filtered dashboard response");
  assert.match(harness.state.fetches[0].resource, /^api\/v1\/dashboard\?/);
  assert.match(harness.state.fetches[0].resource, /activityMode=Drill/);
  assert.match(harness.state.fetches[0].resource, /comparisonTag=league/);
  assert.equal(location.search, "?activityMode=Drill&completion=Completed&recordingQuality=Partial&comparisonTag=league");
  assert.match(document.getElementById("filterStatus").textContent, /0 matching sessions/);

  const lastGoodData = harness.window.__data;
  document.getElementById("filterTag").value = "server error";
  harness.setFetch(() => jsonResponse(500, { error: "Nope" }));
  await document.getElementById("dashboardFilterForm").emit("submit");
  await waitFor(
    () => document.getElementById("filterStatus").textContent === "HTTP 500",
    "filter error state"
  );
  assert.equal(harness.window.__data, lastGoodData);
  assert.equal(document.getElementById("filterStatus").textContent, "HTTP 500");
  assert.equal(document.getElementById("filterStatus").classList.contains("error"), true);
  assert.equal(document.getElementById("applyFilters").disabled, false);
  assert.equal(document.getElementById("resetFilters").disabled, false);

  harness.setFetch(() => jsonResponse(200, emptyDashboard("reset")));
  await document.getElementById("resetFilters").emit("click");
  await waitFor(() => harness.window.__data?.marker === "reset", "reset dashboard response");
  assert.equal(document.getElementById("filterActivity").value, "");
  assert.equal(document.getElementById("filterCompletion").value, "");
  assert.equal(document.getElementById("filterQuality").value, "");
  assert.equal(document.getElementById("filterTag").value, "");
  assert.equal(location.search, "");
  assert.equal(harness.state.fetches[0].resource, "api/v1/dashboard");
});

test("a deep-linked session fetch renders reviewed detail and an editable diary", async () => {
  const harness = createHarness({
    hash: "#/session/session-1",
    recordBootstrapFetches: true,
    bootstrapFetch: resource => {
      if (resource === "api/v1/sessions/session-1/detail") {
        return jsonResponse(200, detailEnvelope());
      }
      if (resource === "api/v1/dashboard") return jsonResponse(200, emptyDashboard());
      if (resource.startsWith("api/v1/captures/")) return jsonResponse(404, { error: "No captures" });
      throw new Error(`Unexpected request ${resource}`);
    }
  });

  await waitFor(() => harness.document.getElementById("sessionDiaryForm"), "session diary form");

  assert.equal(
    harness.state.fetches.filter(call => call.resource === "api/v1/sessions/session-1/detail").length,
    1
  );
  const content = harness.document.getElementById("content").textContent;
  assert.match(content, /Reviewed detected hits/);
  assert.match(content, /Raw detector evidence/);
  assert.match(harness.document.getElementById("subtitle").textContent, /^Session ·/);
  const form = harness.document.getElementById("sessionDiaryForm");
  assert.equal(form.elements.activityMode.value, "Drill");
  assert.equal(form.elements.notes.value, "Original note");
});

test("diary save sends the loaded revision, rerenders the accepted record, and refreshes aggregates", async () => {
  const harness = createHarness();
  harness.hooks.renderSession(detailEnvelope());
  const originalForm = harness.document.getElementById("sessionDiaryForm");
  originalForm.elements.notes.value = "Player corrected note";
  const savedDetail = detailEnvelope({ revision: 2, notes: "Player corrected note", detectedHits: 3 });
  harness.setFetch((resource, init) => {
    if (resource.endsWith("/diary")) return jsonResponse(200, { diaryRevision: 2 });
    if (resource.endsWith("/detail")) return jsonResponse(200, savedDetail);
    if (resource.startsWith("api/v1/dashboard")) return jsonResponse(200, emptyDashboard("after-save"));
    throw new Error(`Unexpected request ${resource}`);
  });

  await originalForm.emit("submit");
  await waitFor(() => harness.window.__data?.marker === "after-save", "post-save aggregate refresh");

  const put = harness.state.fetches.find(call => call.init.method === "PUT");
  assert.ok(put);
  const payload = JSON.parse(put.init.body);
  assert.equal(payload.baseDiaryRevision, 1);
  assert.equal(payload.notes, "Player corrected note");
  assert.equal(new Headers(put.init.headers).get("Content-Type"), "application/json");
  assert.equal(harness.document.getElementById("sessionDiaryForm").elements.notes.value, "Player corrected note");
  assert.equal(
    harness.document.getElementById("diaryStatus").textContent,
    "Diary saved. Recorded session data is unchanged."
  );
});

test("a 409 waits for fresh detail and aggregates, then replaces the stale form with a conflict message", async () => {
  const harness = createHarness();
  harness.hooks.renderSession(detailEnvelope({ revision: 1, notes: "Stale browser note" }));
  const staleForm = harness.document.getElementById("sessionDiaryForm");
  const staleStatus = harness.document.getElementById("diaryStatus");
  const detailResponse = deferred();
  const dashboardResponse = deferred();
  harness.setFetch((resource, init) => {
    if (resource.endsWith("/diary") && init.method === "PUT") {
      return jsonResponse(409, { error: "Diary revision conflict" });
    }
    if (resource.endsWith("/detail")) return detailResponse.promise;
    if (resource.startsWith("api/v1/dashboard")) return dashboardResponse.promise;
    throw new Error(`Unexpected request ${resource}`);
  });

  const submission = staleForm.emit("submit");
  await waitFor(() => harness.state.fetches.length === 3, "conflict reload requests");
  assert.equal(staleStatus.textContent, "Saving diary…");

  detailResponse.resolve(jsonResponse(200, detailEnvelope({ revision: 2, notes: "Latest server note" })));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(staleStatus.textContent, "Saving diary…", "message waits for the aggregate reload too");
  dashboardResponse.resolve(jsonResponse(200, emptyDashboard("after-conflict")));
  await submission;

  const latestForm = harness.document.getElementById("sessionDiaryForm");
  assert.notEqual(latestForm, staleForm);
  assert.equal(latestForm.elements.notes.value, "Latest server note");
  assert.equal(harness.window.__data.marker, "after-conflict");
  assert.equal(
    harness.document.getElementById("diaryStatus").textContent,
    "Diary changed on the server. Latest version loaded; review and save again."
  );
  assert.equal(harness.document.getElementById("diaryStatus").classList.contains("error"), true);
});

test("archive restore confirms and refreshes on success while preserving an actionable server error", async () => {
  const harness = createHarness({ hash: "#/session/session-1" });
  const archiveInput = harness.document.getElementById("archiveFile");
  const archive = { name: "owner-backup.json" };
  archiveInput.files = [archive];
  harness.setFetch((resource, init) => {
    if (resource === "api/v1/import/archive") {
      assert.equal(init.body, archive);
      assert.equal(new Headers(init.headers).get("Content-Type"), "application/json");
      return jsonResponse(200, {
        sessions: { created: 1, replaced: 1, unchanged: 2 },
        captures: { created: 0, replaced: 1, unchanged: 3 }
      });
    }
    if (resource.startsWith("api/v1/dashboard")) return jsonResponse(200, emptyDashboard("restored"));
    throw new Error(`Unexpected request ${resource}`);
  });

  await archiveInput.emit("change");
  assert.equal(harness.location.hash, "#/");
  assert.equal(harness.window.__data.marker, "restored");
  assert.equal(
    harness.document.getElementById("transferStatus").textContent,
    "Restore complete: 3 changed, 5 already current."
  );
  assert.equal(harness.document.getElementById("restoreArchive").disabled, false);

  const rejectedArchive = { name: "broken.json" };
  archiveInput.files = [rejectedArchive];
  harness.setFetch(resource => {
    assert.equal(resource, "api/v1/import/archive");
    return jsonResponse(400, { error: "Archive checksum mismatch" });
  });
  await archiveInput.emit("change");
  assert.equal(harness.state.fetches.length, 1);
  assert.equal(harness.document.getElementById("transferStatus").textContent, "Archive checksum mismatch");
  assert.equal(harness.document.getElementById("transferStatus").classList.contains("error"), true);
  assert.equal(harness.document.getElementById("restoreArchive").disabled, false);
});
