export const EMAIL_CATEGORIES = [
  { value: null, label: 'Todas', icon: 'M4 6h16v12H4zM4 7l8 6 8-6' },
  {
    value: 'professional opportunities and recruitment',
    label: 'Vagas',
    icon: 'M4 8h16v12H4zM9 8V5h6v3M4 13h16M10 13v2h4v-2',
  },
  { value: 'event invitations', label: 'Eventos', icon: 'M4 6h16v14H4zM8 4v4m8-4v4M4 10h16' },
  { value: 'social media updates', label: 'Redes sociais', icon: 'M4 5h16v11H9l-5 4zM8 10h8' },
  { value: 'advertising', label: 'Anúncios', icon: 'M4 10h5l8-5v14l-8-5H4zM7 14l2 5m11-7h2' },
  { value: 'other', label: 'Outros', icon: 'M5 11v2m7-2v2m7-2v2' },
  {
    value: 'potential fraud',
    label: 'Possível fraude',
    description: 'Classificação legada',
    icon: 'M12 4 3 20h18zM12 10v4m0 3v.1',
  },
  { value: '', label: 'Sem categoria', icon: 'M4 5h7l9 9-6 6-10-9zM8 9h.1m3 7 5-5' },
];

export const MANUAL_CATEGORIES = EMAIL_CATEGORIES.filter(
  (option) => option.value && !option.description,
);
