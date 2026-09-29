import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Link,
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
  useOutletContext,
  useSearchParams,
} from "react-router-dom";
import { ApiError, command, get, resetCsrf, send } from "./api/client";
import type { Me, Membership } from "./api/client";
import { useCommand } from "./api/queries";
import {
  getRawMaxInitData,
  nativeBack,
  needsMaxRelaunch,
  MAX_APP_URL,
  openMaxApp,
} from "./platform/max";
import { AboutPage, TestDataBanner } from "./features/about";
import {
  Button,
  CommandError,
  Dialog,
  ErrorPanel,
  Field,
  Icon,
  Notice,
  Title,
  Toast,
  submit,
} from "./components/ui";
import {
  Brand,
  BrandMark,
  BrandSplash,
  StateIllustration,
} from "./components/brand";
import { PreferencesPanel } from "./components/preferences";
export type Session = {
  me: Me;
  house?: Membership;
  selectHouse(houseId: string): void;
  refreshAccess(): void;
  logout(): void;
};
export const useSession = () => useOutletContext<Session>();
function DraftCopy() {
  const [text, setText] = useState<string>();
  return (
    <>
      <Button
        kind="secondary"
        onClick={() => {
          // Read the visible user's form, including disabled controls after 401.
          // Hidden fields, passwords, cookies and Bridge data are never included.
          const controls = document.querySelectorAll<
            HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
          >(
            "#main-content textarea, #main-content input:not([type]), #main-content input[type=text], #main-content input[type=datetime-local], #main-content select",
          );
          setText(
            Array.from(controls)
              .filter(
                (control) =>
                  control.getClientRects().length && control.value.trim(),
              )
              .map((control) => {
                const label = control.labels?.[0]?.textContent?.trim() || "";
                const value =
                  control instanceof HTMLSelectElement
                    ? control.selectedOptions[0]?.text || control.value
                    : control.value;
                return label ? `${label}:\n${value}` : value;
              })
              .join("\n\n"),
          );
        }}
      >
        Подготовить текст для копирования
      </Button>
      {text !== undefined &&
        (text ? (
          <div className="field">
            <label htmlFor="session-draft-copy">Текст для копирования</label>
            <textarea
              id="session-draft-copy"
              readOnly
              rows={6}
              value={text}
              onFocus={(event) => event.currentTarget.select()}
            />
            <p className="caption">
              Выделите и скопируйте этот текст перед закрытием. После нового
              входа его можно вставить в форму.
            </p>
          </div>
        ) : (
          <p>
            На этой странице нет заполненных текстовых полей. Сохранённые
            обращения останутся в сервисе.
          </p>
        ))}
    </>
  );
}
async function enter(signal?: AbortSignal): Promise<Me> {
  try {
    return await get<Me>("/me", signal);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
    const initData = getRawMaxInitData();
    if (!initData || needsMaxRelaunch()) throw error;
    await send<Me>(command("/auth/max", { initData }));
    return get<Me>("/me", signal);
  }
}
export function App() {
  const cache = useQueryClient();
  const navigation = useRef<HTMLElement>(null);
  const [signedOut, setSignedOut] = useState(false);
  const session = useQuery({
    queryKey: ["session"],
    queryFn: ({ signal }) => enter(signal),
    enabled: !signedOut,
    staleTime: Infinity,
  });
  const [expired, setExpired] = useState(false);
  const [search] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const me = session.data;
  const [selectedHouse, setSelectedHouse] = useState<{
    userId: string;
    houseId: string;
  }>();
  const selectHouse = useCallback(
    (houseId: string) => {
      if (me?.memberships.some((m) => m.houseId === houseId)) {
        setSelectedHouse((current) =>
          current?.userId === me.userId && current.houseId === houseId
            ? current
            : { userId: me.userId, houseId },
        );
      }
    },
    [me],
  );
  const house =
    me?.memberships.find((m) => m.houseId === search.get("house")) ||
    me?.memberships.find(
      (m) =>
        selectedHouse?.userId === me.userId &&
        m.houseId === selectedHouse.houseId,
    ) ||
    me?.memberships[0];
  useEffect(() => {
    if (house) selectHouse(house.houseId);
  }, [house?.houseId, selectHouse]);
  const reenter = useCommand<Me>((result) => {
    if (me && result.userId !== me.userId) {
      cache.clear();
      window.location.replace("/");
      return;
    }
    cache.setQueryData(["session"], result);
    setExpired(false);
    void cache.invalidateQueries({
      predicate: (q) => q.queryKey[0] !== "session",
    });
  });
  const relaunch =
    needsMaxRelaunch() ||
    (reenter.error instanceof ApiError &&
      reenter.error.code === "INVALID_MAX_INIT_DATA");
  useEffect(() => {
    const listener = () => setExpired(true);
    window.addEventListener("koren:session-expired", listener);
    return () => window.removeEventListener("koren:session-expired", listener);
  }, []);
  useEffect(() => {
    if (me && session.error instanceof ApiError && session.error.status === 401)
      setExpired(true);
  }, [me, session.error]);
  useEffect(() => {
    const match = /^\/cases\/([^/]+)\//.exec(location.pathname);
    return nativeBack(
      location.pathname === "/"
        ? null
        : () => navigate(match ? `/cases/${match[1]}` : "/"),
    );
  }, [location.pathname, navigate]);
  useEffect(() => {
    const nav = navigation.current;
    if (!nav) return;
    // The mobile bar grows with text zoom and safe areas; reserve its actual height.
    const measure = () => {
      const height =
        getComputedStyle(nav).position === "fixed"
          ? nav.getBoundingClientRect().height
          : 0;
      document.documentElement.style.setProperty(
        "--navigation-height",
        `${height}px`,
      );
    };
    const observer = new ResizeObserver(measure);
    observer.observe(nav);
    measure();
    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty("--navigation-height");
    };
  }, [!!me, signedOut]);
  const context: Session | null = me
    ? {
        me,
        house,
        selectHouse,
        refreshAccess: () => {
          void cache.invalidateQueries({ queryKey: ["session"] });
        },
        logout: () => {
          setSignedOut(true);
          setSelectedHouse(undefined);
          cache.clear();
          resetCsrf();
          navigate("/", { replace: true });
        },
      }
    : null;
  return (
    <div
      className={`shell ${!["/", "/access", "/profile"].includes(location.pathname) ? "nested-page" : ""} ${me && !signedOut ? "authenticated" : "guest"} ${session.isPending && !signedOut ? "booting" : ""}`}
    >
      <a
        className="skip"
        href="#main-content"
        onClick={(event) => {
          event.preventDefault();
          document.getElementById("main-content")?.focus();
        }}
      >
        К содержимому
      </a>
      <aside className="app-sidebar">
        <header className="app-header">
          <Link
            className="brand"
            to={house ? `/?house=${house.houseId}` : "/"}
            aria-label="ДомПульс, главная"
          >
            <Brand />
          </Link>
          <PreferencesPanel />
        </header>
        {me && !signedOut && (
          <nav
            ref={navigation}
            className="app-nav"
            aria-label="Основная навигация"
          >
            <NavLink
              end
              to={house ? `/?house=${house.houseId}` : "/"}
              className={({ isActive }) =>
                isActive ||
                location.pathname.startsWith("/cases/") ||
                location.pathname === "/new"
                  ? "active"
                  : ""
              }
            >
              <Icon name="home" />
              <span>Обращения</span>
            </NavLink>
            <NavLink to="/access">
              <Icon name="lock" />
              <span>Доступ к дому</span>
            </NavLink>
            <NavLink to="/profile">
              <Icon name="user" />
              <span>Профиль</span>
            </NavLink>
          </nav>
        )}
      </aside>
      <main id="main-content" tabIndex={-1}>
        <TestDataBanner />
        {location.pathname === "/about" ? (
          <AboutPage backTo={me && !signedOut ? "/profile" : "/"} />
        ) : signedOut ? (
          <section className="card">
            <Title>Вы вышли</Title>
            <p>Данные сессии удалены с этого устройства.</p>
            {needsMaxRelaunch() ? (
              <a
                className="button primary"
                href={MAX_APP_URL}
                onClick={openMaxApp}
              >
                Открыть заново в MAX
              </a>
            ) : (
              <Button
                onClick={() => {
                  setSignedOut(false);
                  void session.refetch();
                }}
              >
                Войти через MAX
              </Button>
            )}
          </section>
        ) : session.isPending ? (
          <BrandSplash />
        ) : !me ? (
          <section className="welcome">
            <BrandMark className="welcome-logo" />
            <p className="welcome-name">
              Дом<span>Пульс</span>
            </p>
            <Title>Каждая проблема остаётся на виду</Title>
            <p>
              Сообщите о проблеме в доме, следите за каждым пунктом и проверяйте
              результат.
            </p>
            {needsMaxRelaunch() ||
            (session.error instanceof ApiError &&
              session.error.code === "INVALID_MAX_INIT_DATA") ? (
              <>
                <Notice>
                  Откройте мини-приложение из чата с ботом в MAX. Для нового
                  входа закройте его и нажмите «Открыть приложение» в боте. В
                  обычном браузере новый вход недоступен.
                </Notice>
                <p>
                  <a
                    className="button primary"
                    href={MAX_APP_URL}
                    onClick={openMaxApp}
                  >
                    Открыть в MAX
                  </a>
                </p>
              </>
            ) : (
              <ErrorPanel error={session.error} />
            )}
            <div className="actions">
              <Button
                kind="secondary"
                onClick={() => {
                  resetCsrf();
                  void session.refetch();
                }}
              >
                Проверить вход
              </Button>
            </div>
            <Link className="text-link" to="/about">
              О сервисе и удалении данных
            </Link>
          </section>
        ) : (
          context && (
            <div className="page-content" key={location.pathname}>
              <Outlet context={context} />
            </div>
          )
        )}
      </main>
      {expired && me && (
        <Dialog title="Войдите снова" onCancel={() => setExpired(false)}>
          <p>Сессия завершилась. Несохранённый ввод остаётся на странице.</p>
          {relaunch ? (
            <>
              <Notice>
                Данные запуска MAX устарели. Скопируйте несохранённый текст,
                затем закройте мини-приложение и откройте его снова кнопкой в
                боте. Обычное обновление страницы не обновляет вход. После
                открытия проверьте, сохранилось ли ваше действие.
              </Notice>
              <DraftCopy />
            </>
          ) : (
            <CommandError operation={reenter} />
          )}
          <div className="actions">
            {relaunch ? (
              <a
                className="button primary"
                href={MAX_APP_URL}
                onClick={openMaxApp}
              >
                Открыть заново в MAX
              </a>
            ) : (
              <Button
                disabled={reenter.isPending || !getRawMaxInitData()}
                onClick={() =>
                  reenter.run("/auth/max", { initData: getRawMaxInitData() })
                }
              >
                Войти через MAX
              </Button>
            )}
            <Button kind="secondary" onClick={() => setExpired(false)}>
              Вернуться к вводу
            </Button>
          </div>
          {!getRawMaxInitData() && (
            <p>Для нового входа откройте ДомПульс из MAX.</p>
          )}
        </Dialog>
      )}
    </div>
  );
}
export function AccessPage() {
  const { me, refreshAccess } = useSession();
  const cache = useQueryClient();
  const [code, setCode] = useState("");
  const [success, setSuccess] = useState(false);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<unknown>();
  const [accessChecked, setAccessChecked] = useState(false);
  function applyAccess(result: Me) {
    if (result.userId !== me.userId) {
      cache.clear();
      window.location.replace("/");
      return false;
    }
    cache.setQueryData(["session"], result);
    return true;
  }
  const claim = useCommand<Me>((result) => {
    if (!applyAccess(result)) return;
    setCode("");
    setSuccess(true);
  });
  const unknownClaim =
    claim.error instanceof ApiError && claim.error.unknownOutcome;
  async function checkAccess() {
    setChecking(true);
    setCheckError(undefined);
    try {
      const current = await get<Me>("/me");
      if (!applyAccess(current)) return;
      claim.reset();
      setAccessChecked(true);
    } catch (error) {
      setCheckError(error);
      if (error instanceof ApiError && error.status === 401)
        window.dispatchEvent(new Event("koren:session-expired"));
    } finally {
      setChecking(false);
    }
  }
  return (
    <>
      <Title>Доступ к дому</Title>
      {!me.memberships.length && <StateIllustration kind="house" />}
      {me.memberships.length > 0 && (
        <section className="card">
          <h2>Подключённые дома</h2>
          <ul className="membership-list">
            {me.memberships.map((m) => (
              <li key={m.houseId}>
                <span className="house-icon">
                  <Icon name="home" />
                </span>
                <Link className="house-link" to={`/?house=${m.houseId}`}>
                  <strong>{m.address}</strong>
                  <small className="role-badge">{roleLabel(m.role)}</small>
                </Link>
                <Icon name="arrow" />
              </li>
            ))}
          </ul>
        </section>
      )}
      <section className="card">
        <h2>Подключить дом</h2>
        <p className="muted">
          Введите одноразовый код, который вам передал диспетчер дома.
        </p>
        {success && <Toast>Дом успешно подключён! Доступ обновлён.</Toast>}
        <form
          onSubmit={(e) =>
            submit(e, () => {
              setSuccess(false);
              setAccessChecked(false);
              claim.run("/memberships/claim", { invitationCode: code.trim() });
            })
          }
        >
          <fieldset disabled={claim.locked}>
            <Field
              label="Код приглашения"
              hint="Нужен отдельный код приглашения. ID дома сюда не подходит."
              value={code}
              onChange={(value) => {
                setCode(value);
                setSuccess(false);
                setAccessChecked(false);
              }}
            />
            <Button type="submit" disabled={!code.trim()}>
              Подключить дом
            </Button>
          </fieldset>
          {unknownClaim ? (
            <>
              <Notice tone="warning">
                Ответ не получен. Дом мог подключиться. Код одноразовый: сначала
                проверьте список подключённых домов.
              </Notice>
              <ErrorPanel error={checkError} />
              <Button
                kind="secondary"
                disabled={checking}
                onClick={() => void checkAccess()}
              >
                {checking ? "Проверяем доступ…" : "Проверить подключённые дома"}
              </Button>
            </>
          ) : (
            <CommandError operation={claim} />
          )}
          {accessChecked && (
            <Notice>
              Доступ обновлён. Проверьте список домов выше. Если нужного дома
              нет, запросите новый код у диспетчера.
            </Notice>
          )}
          {claim.isPending && <p role="status">Проверяем код…</p>}
        </form>
      </section>
      <Notice>Если у вас нет кода, обратитесь к диспетчеру вашего дома.</Notice>
      <div className="actions">
        <Button kind="secondary" onClick={refreshAccess}>
          Обновить доступ
        </Button>
        <Link className="text-link" to="/">
          К обращениям
        </Link>
      </div>
      <div className="account-exit">
        <Link className="text-link" to="/logout">
          Выйти
        </Link>
      </div>
    </>
  );
}
export function ProfilePage() {
  const { me } = useSession();
  const handle = me.username ? `@${me.username.replace(/^@/, "")}` : null;
  const name = me.displayName || handle || "Пользователь MAX";
  return (
    <>
      <Title>Профиль</Title>
      <section className="card profile-card">
        <ProfileAvatar key={me.avatarUrl} src={me.avatarUrl} name={name} />
        <h2>{name}</h2>
        {handle && me.displayName && <p className="profile-handle">{handle}</p>}
        <p className="muted">Вы вошли через MAX</p>
      </section>
      <section className="card">
        <h2>Мои дома и роли</h2>
        {me.memberships.length ? (
          <ul className="membership-list">
            {me.memberships.map((m) => (
              <li key={m.houseId}>
                <span className="house-icon">
                  <Icon name="home" />
                </span>
                <Link className="house-link" to={`/?house=${m.houseId}`}>
                  <strong>{m.address}</strong>
                  <small className="role-badge">{roleLabel(m.role)}</small>
                </Link>
                <Icon name="arrow" />
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">Пока нет подключённых домов.</p>
        )}
        <Link className="text-link" to="/access">
          Подключить дом
        </Link>
      </section>
      <section className="card">
        <h2>Удобно для вас</h2>
        <p className="muted">
          Тема, размер текста и анимации меняются кнопкой настроек в верхней
          части экрана.
        </p>
      </section>
      <div className="actions">
        <Link className="button secondary" to="/logout">
          Выйти
        </Link>
        <Link className="button secondary" to="/about">
          О сервисе и удалении данных
        </Link>
      </div>
    </>
  );
}
function ProfileAvatar({ src, name }: { src: string | null; name: string }) {
  const [failed, setFailed] = useState(false);
  return (
    <span className="profile-avatar">
      {src && !failed ? (
        <img
          src={src}
          alt={`Фото профиля ${name}`}
          width="64"
          height="64"
          decoding="async"
          referrerPolicy="no-referrer"
          onError={() => setFailed(true)}
        />
      ) : (
        <Icon name="user" />
      )}
    </span>
  );
}
export function LogoutPage() {
  const { logout } = useSession();
  const operation = useCommand<void>(logout);
  return (
    <section className="card">
      <Title>Выйти из ДомПульса?</Title>
      <p>Серверная сессия будет завершена. Войти снова можно через MAX.</p>
      <CommandError operation={operation} />
      <div className="actions">
        <Button
          disabled={operation.locked}
          onClick={() => operation.run("/auth/logout", {})}
        >
          Завершить сессию
        </Button>
        <Link className="button secondary" to="/access">
          Остаться в ДомПульсе
        </Link>
      </div>
    </section>
  );
}
export const roleLabel = (role: Membership["role"]) =>
  ({ RESIDENT: "Житель", DISPATCHER: "Диспетчер", EXECUTOR: "Исполнитель" })[
    role
  ];
