import { useEffect, useState } from "react";
import { Select } from "./ui";
type Preferences = { theme: "system" | "light" | "dark"; large: boolean };
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
    };
  } catch {
    return { theme: "system", large: false };
  }
}
export function PreferencesPanel() {
  const [value, set] = useState(read);
  const [unavailable, setUnavailable] = useState(false);
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
      document
        .querySelector('meta[name="theme-color"]')
        ?.setAttribute(
          "content",
          document.documentElement.dataset.theme === "dark"
            ? "#0F0F12"
            : "#F5F7FA",
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
    <details className="preferences">
      <summary>Настройки вида</summary>
      <div className="preferences-body">
        <Select
          label="Тема"
          value={value.theme}
          onChange={(theme) =>
            set({ ...value, theme: theme as Preferences["theme"] })
          }
        >
          <option value="system">Как на устройстве</option>
          <option value="light">Светлая</option>
          <option value="dark">Тёмная</option>
        </Select>
        <label className="choice">
          <input
            type="checkbox"
            checked={value.large}
            onChange={(e) => set({ ...value, large: e.target.checked })}
          />
          Крупный текст
        </label>
        <p className="caption">
          Уведомления настраиваются в чате с ботом в MAX.
        </p>
        {unavailable && (
          <p className="caption">
            Сохранить настройки на устройстве не удалось. Они действуют до
            закрытия страницы.
          </p>
        )}
      </div>
    </details>
  );
}
