(ns discussion-gems.experiments.detecting-hostile-comments
  "NLP (Sentiment Analysis) for detecting comments that are markedly not
  made in the spirit of a calm / friendly / empathetic discussion,
  rather are clearly antigonizing.

  One might say, comments of a tone
  that you would not have with your colleagues or mother-in-law
  (unless you're already in an open war with them.)

  Examples in French:
  - N'importe quoi.
  - Quelle mauvaise foi.
  - Quel argument bidon.
  - Bullshit.
  - Mais oui, c'est ça.
  - Bel exemple d'homme de paille.
  - Apprends à lire
  - Pathétique.

  The goal is too rule out easy but frequent cases of comments that are not
  part of a quality discussion. Even when those comments contain sound or
  informative arguments, they can be so unpleasant to read that one can expect
  users to not be interested in them in search results.")







