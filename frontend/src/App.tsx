import { useEffect, useState } from "react";
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
  Loading,
  Notice,
  Title,
  submit,
} from "./components/ui";
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
    <div className="shell">
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
      <header className="app-header">
        <Link
          className="brand"
          to={house ? `/?house=${house.houseId}` : "/"}
          aria-label="КОРЕНЬ, главная"
        >
          <svg
            className="brand-mark"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M12 21V3M12 8l6-4M12 13 6 9M12 18l-5 4M12 18l5 4" />
          </svg>
          <span>КОРЕНЬ</span>
        </Link>
        <PreferencesPanel />
      </header>
      {me && !signedOut && (
        <nav className="app-nav" aria-label="Основная навигация">
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
            Обращения
          </NavLink>
          <NavLink to="/access">Доступ к дому</NavLink>
        </nav>
      )}
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
          <Loading />
        ) : !me ? (
          <section className="welcome">
            <span className="welcome-symbol">
              <Icon name="home" />
            </span>
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
            <p>Для нового входа откройте КОРЕНЬ из MAX.</p>
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
      {me.memberships.length > 0 && (
        <section className="card">
          <h2>Подключённые дома</h2>
          <ul className="membership-list">
            {me.memberships.map((m) => (
              <li key={m.houseId}>
                <Icon name="home" />
                <span>
                  <strong>{m.address}</strong>
                  <small>{roleLabel(m.role)}</small>
                </span>
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
          <Notice tone="success">
            Доступ обновлён. Можно перейти к обращениям.
          </Notice>
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
export function LogoutPage() {
  const { logout } = useSession();
  const operation = useCommand<void>(logout);
  return (
    <section className="card">
      <Title>Выйти из КОРНЯ?</Title>
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
          Остаться в КОРНЕ
        </Link>
      </div>
    </section>
  );
}
export const roleLabel = (role: Membership["role"]) =>
  ({ RESIDENT: "Житель", DISPATCHER: "Диспетчер", EXECUTOR: "Исполнитель" })[
    role
  ];
