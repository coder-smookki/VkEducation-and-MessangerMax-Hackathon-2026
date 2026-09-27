/// <reference types="vite/client" />
import { Link } from "react-router-dom";
import { Icon, Notice, Title } from "../components/ui";

const supportEmail = "smookki@yandex.ru";
const deletionEmail = `mailto:${supportEmail}?subject=${encodeURIComponent("ДомПульс: запрос на удаление данных")}`;

export function TestDataBanner() {
  if (import.meta.env.VITE_TRAINING_MODE !== "true") return null;
  return (
    <section className="training-banner" aria-label="Тестовый стенд">
      <Notice>
        <strong>Учебный дом, данные тестовые</strong>
        <p>
          Это стенд для проверки сервиса. Не добавляйте сведения о реальных
          жильцах и другие личные данные.
        </p>
      </Notice>
    </section>
  );
}

export function AboutPage({ backTo = "/profile" }: { backTo?: string }) {
  return (
    <div className="about-page">
      <Link className="back-link" to={backTo}>
        <Icon name="arrow" />
        Назад
      </Link>
      <Title>О сервисе</Title>
      <p className="page-description">
        ДомПульс помогает сообщать о проблемах дома, следить за работами и
        проверять результат по каждому пункту обращения.
      </p>

      <Notice tone="warning">
        <strong>ДомПульс не является аварийной службой</strong>
        <p>
          При угрозе жизни, пожаре или другой опасности звоните{" "}
          <a href="tel:112">112</a>. Не ждите ответа в приложении или боте.
        </p>
      </Notice>

      <section className="card" aria-labelledby="about-data-title">
        <h2 id="about-data-title">Какие данные используем и зачем</h2>
        <ul className="about-data-list">
          <li>
            <strong>Аккаунт MAX.</strong> Идентификатор аккаунта связывает вас с
            вашими обращениями. Имя, никнейм и ссылка на аватар, если MAX их
            передал при входе, используются для профиля в текущей серверной
            сессии.
          </li>
          <li>
            <strong>Дом и доступ.</strong> Адрес дома, ваша роль и сведения о
            приглашении нужны, чтобы показывать только разрешённые обращения.
          </li>
          <li>
            <strong>Обращения и работы.</strong> Сохраняем текст и его редакции,
            место и описание каждой проблемы, отчёты, следующий шаг, наблюдения
            автора и причины отзыва или архива. Это позволяет не терять
            нерешённые вопросы.
          </li>
          <li>
            <strong>История и сообщения.</strong> Сохраняем, кто и когда
            выполнил действие, входящие сообщения боту, переданные MAX сведения
            об отправителе и сведения об отправке ответов. Они нужны для
            истории, доставки и защиты от повторов. Технические резервные копии
            также могут содержать эти данные.
          </li>
        </ul>
      </section>

      <section className="card" aria-labelledby="about-access-title">
        <h2 id="about-access-title">Кто видит обращение</h2>
        <p>
          Черновик доступен только его автору с действующим доступом к дому.
          После подтверждения список видят автор и диспетчер этого дома. Бот и
          мини-приложение используют одну историю.
        </p>
        <p>
          Анализ отчёта может предложить связи с пунктами обращения. Эти
          подсказки сохраняются для проверки диспетчером и сами не подтверждают
          ремонт. Автор отдельно сообщает, что видит на месте.
        </p>
      </section>

      <section className="card" aria-labelledby="about-device-title">
        <h2 id="about-device-title">Что хранится на устройстве</h2>
        <p>
          Cookie поддерживают вход и защищают отправку форм. Тема, размер текста
          и настройка анимаций сохраняются в этом браузере. Приложение не
          создаёт постоянную офлайн-копию ваших обращений на устройстве.
        </p>
      </section>

      <section className="card" aria-labelledby="about-delete-title">
        <h2 id="about-delete-title">Как запросить удаление данных</h2>
        <p>
          Напишите команде на{" "}
          <a className="about-email" href={`mailto:${supportEmail}`}>
            {supportEmail}
          </a>
          . Укажите, какие данные хотите удалить, ваш никнейм или идентификатор
          MAX, если он вам известен, и номера обращений, которых касается
          запрос. Не присылайте лишние личные сведения.
        </p>
        <p>
          Не отправляйте пароли, токены и служебные данные входа (initData).
          Команда сверит, что запрос направил владелец данных, уточнит
          затронутые записи, историю и резервные копии и подтвердит обработку
          запроса.
        </p>
        <Notice>
          Автоматического удаления в приложении нет. Архив, отзыв обращения и
          выход из аккаунта не удаляют сохранённые данные.
        </Notice>
        <a className="button secondary" href={deletionEmail}>
          <Icon name="file" />
          Запросить удаление по почте
        </a>
      </section>

      <section className="card" aria-labelledby="about-support-title">
        <h2 id="about-support-title">Помощь с сервисом</h2>
        <p>
          Если приложение работает неправильно, напишите, что произошло и на
          каком экране. Для поиска сбоя можно указать код запроса из сообщения
          об ошибке.
        </p>
        <a className="button primary" href={`mailto:${supportEmail}`}>
          Написать команде
        </a>
      </section>
    </div>
  );
}
