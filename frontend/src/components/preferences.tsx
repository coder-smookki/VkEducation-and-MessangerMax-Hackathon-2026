import { useEffect, useId, useState } from "react";
import { Button, Dialog, Icon } from "./ui";
import "./preferences.css";
type Preferences = {
  theme: "system" | "light" | "dark";
  large: boolean;
  reduceMotion: boolean;
};
function read(): Preferences {
  try {
    const saved = JSON.parse(
      localStorage.getItem("koren:preferences") || "{}",
    ) as Partial<Preferences>;
    return {
      theme: ["light", "dark"].includes(saved.theme || "")
        ? (saved.theme as Preferences["theme"])
        : "system",
      large: saved.large === true,
      reduceMotion: saved.reduceMotion === true,
    };
  } catch {
    return { theme: "system", large: false, reduceMotion: false };
  }
}
export function PreferencesPanel() {
  const id = useId();
  const [value, set] = useState(read);
  const [unavailable, setUnavailable] = useState(false);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const media = matchMedia("(prefers-color-scheme: dark)");
    function apply() {
      document.documentElement.dataset.theme =
        value.theme === "system"
          ? media.matches
            ? "dark"
            : "light"
          : value.theme;
      document.documentElement.dataset.large = String(value.large);
      document.documentElement.dataset.reduceMotion = String(
        value.reduceMotion,
      );
      document
        .querySelector('meta[name="theme-color"]')
        ?.setAttribute(
          "content",
          document.documentElement.dataset.theme === "dark"
            ? "#0C1220"
            : "#F7F9FF",
        );
    }
    apply();
    media.addEventListener("change", apply);
    try {
      localStorage.setItem("koren:preferences", JSON.stringify(value));
      setUnavailable(false);
    } catch {
      setUnavailable(true);
    }
    return () => media.removeEventListener("change", apply);
  }, [value]);
  return (
    <>
      <button
        type="button"
        className="preferences-trigger"
        aria-haspopup="dialog"
        onClick={() => setOpen(true)}
      >
        <Icon name="settings" />
        <span>Настройки вида</span>
      </button>
      {open && (
        <Dialog title="Внешний вид приложения" onCancel={() => setOpen(false)}>
          <div className="preferences-content">
            <button
              type="button"
              className="preferences-close"
              aria-label="Закрыть настройки"
              onClick={() => setOpen(false)}
            >
              <AppearanceIcon name="close" />
            </button>
            <fieldset className="theme-options">
              <legend>Тема оформления</legend>
              <div className="theme-options-grid">
                {(
                  [
                    ["light", "Светлая", "sun"],
                    ["dark", "Тёмная", "moon"],
                    ["system", "Как в системе", "monitor"],
                  ] as const
                ).map(([theme, label, icon]) => (
                  <label
                    key={theme}
                    className={`theme-option ${value.theme === theme ? "selected" : ""}`}
                  >
                    <input
                      type="radio"
                      name={`${id}-theme`}
                      value={theme}
                      checked={value.theme === theme}
                      onChange={() => set({ ...value, theme })}
                    />
                    <AppearanceIcon name={icon} />
                    <span>{label}</span>
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="preferences-switches">
              <label className="preference-switch">
                <span className="preference-icon" aria-hidden="true">
                  Aa
                </span>
                <span className="preference-copy">
                  <strong id={`${id}-large-label`}>Крупный текст</strong>
                  <small id={`${id}-large-help`}>
                    Увеличивает размер текста во всех разделах
                  </small>
                </span>
                <input
                  type="checkbox"
                  role="switch"
                  aria-labelledby={`${id}-large-label`}
                  aria-describedby={`${id}-large-help`}
                  checked={value.large}
                  onChange={(e) => set({ ...value, large: e.target.checked })}
                />
              </label>
              <label className="preference-switch">
                <span className="preference-icon">
                  <AppearanceIcon name="motion" />
                </span>
                <span className="preference-copy">
                  <strong id={`${id}-motion-label`}>Уменьшить анимации</strong>
                  <small id={`${id}-motion-help`}>
                    Делает интерфейс спокойнее. Системные настройки учитываются
                    всегда
                  </small>
                </span>
                <input
                  type="checkbox"
                  role="switch"
                  aria-labelledby={`${id}-motion-label`}
                  aria-describedby={`${id}-motion-help`}
                  checked={value.reduceMotion}
                  onChange={(e) =>
                    set({ ...value, reduceMotion: e.target.checked })
                  }
                />
              </label>
            </div>
            <p className="caption preferences-note">
              Настройки сохраняются на этом устройстве. Уведомления можно
              изменить в чате с ботом в MAX.
            </p>
            {unavailable && (
              <p className="caption" role="status">
                Сохранить настройки на устройстве не удалось. Они действуют до
                закрытия страницы.
              </p>
            )}
            <div className="actions">
              <Button onClick={() => setOpen(false)}>Готово</Button>
            </div>
          </div>
        </Dialog>
      )}
    </>
  );
}

function AppearanceIcon({
  name,
}: {
  name: "sun" | "moon" | "monitor" | "motion" | "close";
}) {
  const paths = {
    sun: "M12 3v1m0 16v1M3 12h1m16 0h1M5.6 5.6l.7.7m11.4 11.4.7.7M5.6 18.4l.7-.7M17.7 6.3l.7-.7M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z",
    moon: "M20 14.3A8.5 8.5 0 0 1 9.7 4 8.5 8.5 0 1 0 20 14.3Z",
    monitor: "M4 4h16v12H4zM12 16v4m-4 0h8",
    motion:
      "M3 10c2-5 9-7 14-3 4 3 3 10-2 12M6 12c1-4 7-5 10-2 2 2 1 6-2 7M10 13c0-2 3-2 4 0m-9 3 2-2 2 2M6 6 4 8 2 6",
    close: "M6 6l12 12M6 18 18 6",
  };
  return (
    <svg
      className="appearance-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name]} />
    </svg>
  );
}
