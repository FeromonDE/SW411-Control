(() => {
  // Keep the server's HTML/CSS/actions. Only the PWA install action is replaced
  // because this page is already running inside the installed Android app.
  const install = document.getElementById('installPwaBtn');
  const menu = document.getElementById('moreMenu');
  if (install && !document.getElementById('androidConnectionBtn')) {
    const connection = install.cloneNode(true);
    connection.id = 'androidConnectionBtn';
    connection.style.display = '';
    connection.querySelector('strong').textContent = 'Адрес сервера';
    connection.querySelector('small').textContent = 'Подключение к Nexbox';
    connection.querySelector('svg').innerHTML = '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>';
    connection.addEventListener('click', () => {
      menu.classList.remove('open');
      document.getElementById('moreBtn')?.setAttribute('aria-expanded', 'false');
      window.location.href = 'sw411-app://connection';
    });
    install.hidden = true;
    install.style.display = 'none';
    install.before(connection);
  }
  window.sw411AndroidCloseOverlay = () => {
    const settings = document.getElementById('settingsModal');
    if (settings?.classList.contains('open')) {
      document.getElementById('closeSettingsBtn').click();
      return true;
    }
    if (menu?.classList.contains('open')) {
      menu.classList.remove('open');
      document.getElementById('moreBtn')?.setAttribute('aria-expanded', 'false');
      return true;
    }
    return false;
  };
})();
