import { useEffect, useRef, useState } from "react";
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
import { getRawMaxInitData, nativeBack } from "./platform/max";
import {
  Button,
  CommandError,
  Dialog,
  ErrorPanel,
  Field,
  Icon,
  Notice,
  Title,
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
  refreshAccess(): void;
  logout(): void;
};
export const useSession = () => useOutletContext<Session>();
async function enter(signal?: AbortSignal): Promise<Me> {
  try {
    return await get<Me>("/me", signal);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
    const initData = getRawMaxInitData();
    if (!initData) throw error;
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
  const house =
    me?.memberships.find((m) => m.houseId === search.get("house")) ||
    me?.memberships[0];
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
  useEffect(() => {
    const listener = () => setExpired(true);
    window.addEventListener("koren:session-expired", listener);
    return () => window.removeEventListener("koren:session-expired", listener);
  }, []);
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
        refreshAccess: () => {
          void cache.invalidateQueries({ queryKey: ["session"] });
        },
        logout: () => {
          setSignedOut(true);
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
        {signedOut ? (
          <section className="card">
            <Title>Вы вышли</Title>
            <p>Данные сессии удалены с этого устройства.</p>
            <Button
              onClick={() => {
                setSignedOut(false);
                void session.refetch();
              }}
            >
              Войти через MAX
            </Button>
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
            {!getRawMaxInitData() ? (
              <>
                <Notice>
                  Откройте мини-приложение из чата с ботом в MAX. В обычном
                  браузере новый вход недоступен.
                </Notice>
                <p>
                  <a
                    className="button primary"
                    href="https://max.ru/t109_hakaton_max_bot?startapp"
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
          <CommandError operation={reenter} />
          <div className="actions">
            <Button
              disabled={reenter.isPending || !getRawMaxInitData()}
              onClick={() =>
                reenter.run("/auth/max", { initData: getRawMaxInitData() })
              }
            >
              Войти через MAX
            </Button>
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
  const [code, setCode] = useState("");
  const [success, setSuccess] = useState(false);
  const claim = useCommand<Me>(() => {
    setCode("");
    setSuccess(true);
    refreshAccess();
  });
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
        {success && (
          <div className="access-success" role="status">
            <StateIllustration kind="success" />
            <h2>Дом успешно подключён!</h2>
            <p>Доступ обновлён. Можно перейти к обращениям.</p>
            <Link className="button primary" to="/">
              Готово
            </Link>
          </div>
        )}
        <form
          onSubmit={(e) =>
            submit(e, () =>
              claim.run("/memberships/claim", { invitationCode: code.trim() }),
            )
          }
        >
          <fieldset disabled={claim.locked}>
            <Field label="Код приглашения" value={code} onChange={setCode} />
            <Button type="submit" disabled={!code.trim()}>
              Подключить дом
            </Button>
          </fieldset>
          <CommandError operation={claim} />
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
      <Link className="button secondary" to="/logout">
        Выйти
      </Link>
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
