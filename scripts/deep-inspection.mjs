import { ChromeSession } from "./chrome-session.mjs";

async function runDeepInspection() {
  const session = await ChromeSession.getActivePage();
  const findings = [];

  function record(
    category,
    page,
    description,
    recommendation,
    severity = "medium",
  ) {
    findings.push({ category, page, description, recommendation, severity });
    console.log(`[${severity.toUpperCase()}] [${page}] ${description}`);
  }

  try {
    await session.connect();
    console.log("=== Connected to Chrome for Deep Full-Page Inspection ===\n");

    // Enable console & network tracking
    await session.send("Console.enable");
    await session.send("Runtime.enable");

    // Helper: evaluate in page
    const evalInPage = (fnStr) => session.eval(fnStr);

    // --- 1. Public Article List & Detail ---
    console.log("--- Inspecting: / (Public Home) ---");
    await session.navigate("http://localhost:4200/");
    await new Promise((r) => setTimeout(r, 600));

    let publicInfo = await evalInPage(`(() => {
      const title = document.querySelector('h1')?.textContent;
      const articles = document.querySelectorAll('article, .article-card, .mat-mdc-row').length;
      const navLinks = Array.from(document.querySelectorAll('nav a, header a')).map(a => ({ text: a.textContent.trim(), href: a.getAttribute('href') }));
      const langBtn = document.querySelector('.language-toggle, button.lang-btn, header button')?.textContent.trim();
      return { title, articles, navLinks, langBtn };
    })()`);

    console.log("Public Home Info:", publicInfo);

    // Click first article to inspect article detail view if available
    let hasArticleLink = await evalInPage(
      `Boolean(document.querySelector('a[href^="/articles/"], article a'))`,
    );
    if (hasArticleLink) {
      console.log("--- Inspecting: /articles/:id (Public Article Detail) ---");
      await evalInPage(
        `document.querySelector('a[href^="/articles/"], article a').click()`,
      );
      await new Promise((r) => setTimeout(r, 600));
      let detailInfo = await evalInPage(`(() => {
        const h1 = document.querySelector('h1')?.textContent;
        const content = document.querySelector('.content, article p, .article-body')?.textContent;
        const backLink = document.querySelector('a[href="/"], .back-link')?.textContent;
        return { h1, hasContent: Boolean(content), backLink };
      })()`);
      console.log("Article Detail Info:", detailInfo);
    }

    // --- 2. Auth & Account Pages ---
    const authPages = [
      { path: "/login", name: "登入 (Login)" },
      { path: "/register", name: "註冊 (Register)" },
      { path: "/verify-email", name: "驗證電子信箱 (Verify Email)" },
      { path: "/verify/resend", name: "重發驗證信 (Resend Verification)" },
      { path: "/password-reset", name: "申請重設密碼 (Password Reset)" },
      { path: "/reset-password", name: "重設密碼 (Reset Password)" },
      { path: "/invite", name: "接受邀請 (Accept Invitation)" },
    ];

    for (const p of authPages) {
      console.log(`--- Inspecting: ${p.path} (${p.name}) ---`);
      await session.navigate(`http://localhost:4200${p.path}`);
      await new Promise((r) => setTimeout(r, 500));

      let pageData = await evalInPage(`(() => {
        const h1 = document.querySelector('h1')?.textContent.trim();
        const inputs = Array.from(document.querySelectorAll('input, select, textarea')).map(el => ({
          id: el.id,
          type: el.type,
          name: el.name,
          hasLabel: Boolean(document.querySelector('label[for="' + el.id + '"]')),
          placeholder: el.placeholder || '',
          ariaDescribedBy: el.getAttribute('aria-describedby') || ''
        }));
        const links = Array.from(document.querySelectorAll('.account-links a')).map(a => a.textContent.trim());
        const submitBtn = document.querySelector('button[type="submit"]')?.textContent.trim();
        return { h1, inputs, links, submitBtn };
      })()`);

      console.log(`Page Data for ${p.path}:`, pageData);

      // Check empty validation
      await evalInPage(
        `document.querySelector('button[type="submit"]')?.click()`,
      );
      await new Promise((r) => setTimeout(r, 200));
      let errors = await evalInPage(
        `Array.from(document.querySelectorAll('.field-error')).map(e => e.textContent.trim()).filter(Boolean)`,
      );
      console.log(`Validation errors on ${p.path}:`, errors);
    }

    // --- 3. Login as Admin & Inspect Protected Pages ---
    console.log("\n--- Logging in as Admin for Protected Pages Inspection ---");
    await session.navigate("http://localhost:4200/login");
    await new Promise((r) => setTimeout(r, 400));
    await session.type("#field-email", "admin@example.com");
    await session.type("#field-password", "admin123456");
    await session.click('button[type="submit"]');
    await new Promise((r) => setTimeout(r, 1000));

    const protectedPages = [
      { path: "/articles", name: "文章管理 (Articles List)" },
      { path: "/articles/new", name: "新增文章 (New Article)" },
      { path: "/articles/deleted", name: "已刪除文章 (Recycle Bin)" },
      { path: "/admin/users", name: "使用者管理 (User Management)" },
      { path: "/admin/invitations", name: "發送邀請 (Invitations)" },
      {
        path: "/admin/settings/password",
        name: "密碼長度設定 (Password Settings)",
      },
      { path: "/account/profile", name: "個人資料 (Profile)" },
      { path: "/account/password", name: "變更密碼 (Change Password)" },
      { path: "/account/email", name: "變更信箱 (Change Email)" },
      { path: "/account/sessions", name: "登入工作階段 (Active Sessions)" },
    ];

    for (const p of protectedPages) {
      console.log(`\n--- Inspecting Protected: ${p.path} (${p.name}) ---`);
      await session.navigate(`http://localhost:4200${p.path}`);
      await new Promise((r) => setTimeout(r, 600));

      let pageData = await evalInPage(`(() => {
        const h1 = document.querySelector('h1')?.textContent.trim();
        const headerNav = Array.from(document.querySelectorAll('header nav a, app-shell nav a')).map(a => ({
          text: a.textContent.trim(),
          href: a.getAttribute('href'),
          active: a.classList.contains('active') || a.getAttribute('aria-current') === 'page'
        }));
        const tables = document.querySelectorAll('table').length;
        const thHeaders = Array.from(document.querySelectorAll('th')).map(th => th.textContent.trim());
        const rows = document.querySelectorAll('tbody tr').length;
        const formControls = Array.from(document.querySelectorAll('input, select, textarea, fieldset')).map(el => ({
          tag: el.tagName.toLowerCase(),
          id: el.id,
          name: el.getAttribute('name') || '',
          type: el.getAttribute('type') || ''
        }));
        const buttons = Array.from(document.querySelectorAll('button')).map(b => ({
          text: b.textContent.trim(),
          type: b.getAttribute('type'),
          disabled: b.disabled
        }));
        const pagination = Boolean(document.querySelector('.pagination'));
        return { h1, headerNav, tables, thHeaders, rows, formControls, buttons, pagination };
      })()`);

      console.log(
        `Protected Page ${p.path} Analysis:`,
        JSON.stringify(pageData, null, 2),
      );
    }
  } catch (err) {
    console.error("Inspection error:", err);
  } finally {
    session.close();
  }
}

runDeepInspection();
