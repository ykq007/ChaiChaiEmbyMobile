const variants = [
  { key: "A", name: "Spotlight shelves" },
  { key: "B", name: "Library workbench" },
  { key: "C", name: "Continuity canvas" },
];

const params = new URLSearchParams(window.location.search);
const initialVariant = variants.some(({ key }) => key === params.get("variant")) ? params.get("variant") : "A";
const initialWidth = ["compact", "medium", "expanded"].includes(params.get("width")) ? params.get("width") : "expanded";
const initialView = ["home", "library", "search", "details"].includes(params.get("view")) ? params.get("view") : "home";

const app = document.querySelector("#app");
const device = document.querySelector("#device");
const label = document.querySelector("#variant-label");

function updateUrl() {
  const next = new URL(window.location.href);
  next.searchParams.set("variant", app.dataset.variant);
  next.searchParams.set("width", [...device.classList].find((name) => ["compact", "medium", "expanded"].includes(name)));
  next.searchParams.set("view", app.dataset.view);
  window.history.replaceState({}, "", next);
}

function selectVariant(key) {
  const variant = variants.find((item) => item.key === key) ?? variants[0];
  app.dataset.variant = variant.key;
  label.textContent = `${variant.key} — ${variant.name}`;
  updateUrl();
}

function cycleVariant(direction) {
  const index = variants.findIndex(({ key }) => key === app.dataset.variant);
  selectVariant(variants[(index + direction + variants.length) % variants.length].key);
}

function selectWidth(width) {
  device.classList.remove("compact", "medium", "expanded");
  device.classList.add(width);
  document.querySelectorAll("[data-width]").forEach((button) => button.setAttribute("aria-pressed", String(button.dataset.width === width)));
  updateUrl();
}

function selectView(view) {
  app.dataset.view = view;
  document.querySelectorAll("[data-view]").forEach((button) => {
    if (button.closest(".segmented")) button.setAttribute("aria-pressed", String(button.dataset.view === view));
    if (button.closest(".rail, .bottom-nav")) button.classList.toggle("active", button.dataset.view === view);
  });
  updateUrl();
}

document.querySelector("#previous").addEventListener("click", () => cycleVariant(-1));
document.querySelector("#next").addEventListener("click", () => cycleVariant(1));
document.querySelectorAll("[data-width]").forEach((button) => button.addEventListener("click", () => selectWidth(button.dataset.width)));
document.querySelectorAll("[data-view]").forEach((button) => button.addEventListener("click", () => selectView(button.dataset.view)));
document.addEventListener("keydown", (event) => {
  if (["INPUT", "TEXTAREA"].includes(document.activeElement.tagName) || document.activeElement.isContentEditable) return;
  if (event.key === "ArrowLeft") cycleVariant(-1);
  if (event.key === "ArrowRight") cycleVariant(1);
});

selectVariant(initialVariant);
selectWidth(initialWidth);
selectView(initialView);
