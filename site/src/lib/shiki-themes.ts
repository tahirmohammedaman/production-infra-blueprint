// Two syntax themes drawn from the site's own palette. Code is emitted with both sets of
// colours as CSS variables, so switching the page theme needs no re-highlighting.

import type { ThemeRegistrationRaw } from 'shiki';

interface Tones {
  fg: string;
  muted: string;
  comment: string;
  keyword: string;
  string: string;
  number: string;
  fn: string;
  key: string;
}

function theme(name: string, type: 'dark' | 'light', bg: string, t: Tones): ThemeRegistrationRaw {
  return {
    name,
    type,
    colors: { 'editor.background': bg, 'editor.foreground': t.fg },
    settings: [],
    tokenColors: [
      { scope: ['comment', 'punctuation.definition.comment'], settings: { foreground: t.comment, fontStyle: 'italic' } },
      { scope: ['keyword', 'storage', 'storage.type', 'keyword.operator.word', 'constant.language'], settings: { foreground: t.keyword } },
      { scope: ['string', 'string.quoted', 'markup.inline.raw'], settings: { foreground: t.string } },
      { scope: ['constant.numeric', 'constant.character', 'constant.other', 'variable.other.constant'], settings: { foreground: t.number } },
      { scope: ['entity.name.function', 'support.function', 'entity.name.command', 'support.command'], settings: { foreground: t.fn } },
      { scope: ['entity.name.tag', 'support.type.property-name', 'entity.name.type', 'variable.parameter', 'meta.object-literal.key'], settings: { foreground: t.key } },
      { scope: ['punctuation', 'keyword.operator', 'meta.brace'], settings: { foreground: t.muted } },
      { scope: ['variable', 'variable.other'], settings: { foreground: t.fg } },
    ],
  };
}

export const blueprintDark = theme('blueprint-dark', 'dark', '#0F1822', {
  fg: '#CBD7E2',
  muted: '#8597A9',
  comment: '#6C8196',
  keyword: '#7DB4EE',
  string: '#9DD1BA',
  number: '#E2B56E',
  fn: '#B9C8F7',
  key: '#8CC3F0',
});

export const blueprintLight = theme('blueprint-light', 'light', '#F2F5F9', {
  fg: '#1B2A38',
  muted: '#566879',
  comment: '#697A8B',
  keyword: '#1A5490',
  string: '#2A6B4D',
  number: '#8A5A0B',
  fn: '#3A4C9C',
  key: '#1F5F99',
});
