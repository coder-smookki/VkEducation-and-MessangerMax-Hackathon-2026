import { useEffect, useState } from "react";
import { Button, Dialog, Icon, Select } from "./ui";
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
        <Dialog title="Удобный вид" onCancel={() => setOpen(false)}>
          <p className="muted">Настройте КОРЕНЬ так, как удобно вам.</p>
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
            <span>
              <strong>Крупный текст</strong>
              <small>Увеличить надписи и элементы форм</small>
            </span>
          </label>
          <label className="choice">
            <input
              type="checkbox"
              checked={value.reduceMotion}
              onChange={(e) =>
                set({ ...value, reduceMotion: e.target.checked })
              }
            />
            <span>
              <strong>Без анимации</strong>
              <small>Системное уменьшение движения учитывается всегда</small>
            </span>
          </label>
          <p className="caption preferences-note">
            Настройки действуют на этом устройстве. Уведомления можно изменить в
            чате с ботом в MAX.
          </p>
          {unavailable && (
            <p className="caption">
              Сохранить настройки на устройстве не удалось. Они действуют до
              закрытия страницы.
            </p>
          )}
          <div className="actions">
            <Button onClick={() => setOpen(false)}>Готово</Button>
          </div>
        </Dialog>
      )}
    </>
  );
}
