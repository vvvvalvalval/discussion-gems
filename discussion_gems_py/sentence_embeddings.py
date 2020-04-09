import numpy
import sentence_transformers.models
import sklearn.metrics.pairwise
import sentence_transformers

cmb = sentence_transformers.models.CamemBERT('camembert-base')
pooling_model = sentence_transformers.models.Pooling(cmb.get_word_embedding_dimension(), pooling_mode_mean_tokens=True,pooling_mode_cls_token=False,pooling_mode_max_tokens=False)

model = sentence_transformers.SentenceTransformer(modules=[cmb, pooling_model])


def sentences_embeddings(sentences):
    return model.encode(sentences)


def doc_sentence_sim(base_sentence_vecs, doc_sentences):
    return numpy.average(sklearn.metrics.pairwise.cosine_similarity(sentences_embeddings(doc_sentences), base_sentence_vecs))
