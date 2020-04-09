
def addition(x, y):
  return x + y


def cmt_sentence_transformers():
    import sentence_transformers.models
    cmb = sentence_transformers.models.CamemBERT('camembert-base')
    from sentence_transformers import SentenceTransformer
    pooling_model = sentence_transformers.models.Pooling(cmb.get_word_embedding_dimension(), pooling_mode_mean_tokens=True,pooling_mode_cls_token=False,pooling_mode_max_tokens=False)
    model = SentenceTransformer(modules=[cmb, pooling_model])
    sentences = ["Superbe réponse, merci !", "Excellentes remarques, j'ai appris plein de choses, merci.", "Merci pour ton commentaire, très intéressant", "Je ne suis pas vraiment d'accord avec ce que tu dis"]
    from sklearn.metrics.pairwise import cosine_similarity
    cosine_similarity(model.encode(sentences))