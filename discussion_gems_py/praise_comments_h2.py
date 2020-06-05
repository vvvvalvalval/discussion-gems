"""
h2 ('Heuristic 2'): Learning a second intermediary heuristic for Praise Comments.
"""

import random
import numpy as np
import math
import scipy.sparse
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.preprocessing import StandardScaler, Normalizer
import sklearn.linear_model
import sklearn.naive_bayes
import sklearn.ensemble
import sklearn.metrics
from sklearn.model_selection import cross_val_score
import discussion_gems_py.utils.io as uio
import discussion_gems_py.utils.scikit_learn as uskl

def tokenize_identity(tokens_list):
  return tokens_list

def bow_features_matrix(tokens_lists):
  vectorizer = CountVectorizer(tokenizer=tokenize_identity, max_features=5000, lowercase=False)
  X = vectorizer.fit_transform(tokens_lists)
  ## NOTE performance degraded when using StandardScaler (Val, 03 Jun 2020)
  normalizer = Normalizer()#StandardScaler(with_mean=False)
  X = normalizer.fit_transform(X)

  vocab = vectorizer.vocabulary_
  def word_coeffs(coef_v):
    return dict((word, coef_v[word_i]) for (word, word_i) in vocab.items())

  return (X, word_coeffs)


def non_word_features(cmt_dict):
  X = np.array([[
    1. if m['is_post'] else 0.,
    math.log2(1. + m['n_words']),
    m['dgms_n_hyperlinks'],
    m['dgms_n_quotes']
  ] for m in cmt_dict])
  ## NOTE performance degraded when using StandardScaler (Val, 03 Jun 2020)
  normalizer = Normalizer() #StandardScaler(with_mean=False)
  def ftr_coeffs(coef_v):
    return {
      'is_post': coef_v[0],
      'log2_n_words': coef_v[1],
      'dgms_n_hyperlinks': coef_v[2],
      'dgms_n_quotes': coef_v[3]
    }
  X = normalizer.fit_transform(X)
  return X, ftr_coeffs


def h2_features_matrix(feat_dicts):
  cmt_nw_X, get_coeff_0 = non_word_features([m['dgms_comment'] for m in feat_dicts])
  parent_nw_X, get_coeff_1 = non_word_features([m['dgms_parent'] for m in feat_dicts])
  cmt_bow_X, get_coeff_2 = bow_features_matrix([m['dgms_comment']['zoned_tokens'] for m in feat_dicts])
  parent_bow_X, get_coeff_3 = bow_features_matrix([m['dgms_parent']['zoned_tokens'] for m in feat_dicts])
  X = scipy.sparse.hstack([
    cmt_nw_X,
    parent_nw_X,
    cmt_bow_X,
    parent_bow_X
  ]).tocsr()

  def get_model_repr(coef_v, intercept):
    """
    Given the learned attributes for the linear boundary,
    translates it to a data structure expressing it in terms of words.
    :param coef_v:
    :param intercept:
    :return:
    """
    widths = [M.shape[1] for M in [cmt_nw_X, parent_nw_X, cmt_bow_X, parent_bow_X]]
    offsets = [0]
    ofst = 0
    for w in widths:
      ofst += w
      offsets.append(ofst)

    h2_m_repr = { ## INTRO the representation of the learned model. (Val, 04 Jun 2020)
      'intercept': intercept,
      'dgms_comment': {
        'non_word': get_coeff_0(coef_v[offsets[0]: offsets[1]]),
        'BoW': get_coeff_2(coef_v[offsets[2]: offsets[3]])
      },
      'dgms_parent': {
        'non_word': get_coeff_1(coef_v[offsets[1]: offsets[2]]),
        'BoW': get_coeff_3(coef_v[offsets[3]: offsets[4]])
      }
    }
    return h2_m_repr

  return (X, get_model_repr)


def h2_label_vector(feat_maps):
  return np.array([1 if m['dgms_h2_label'] > 0.5 else 0 for m in feat_maps])



def train_h2_linear_classifier(h2_labelled_dataset, cv_k_folds=None):
  """
  Given a list of dictionaries representing comments,
  trains a linear classifier for selecting Praise comments
  with good recall and not-too-bad precision.

  Returns a readable Python data structure (dicts),
  giving an intercept and mapping a coefficient to each feature.

  The features are divided in several subsets (comment/parent x BoW/non-word);
  each subset of the features must normalized to unit L2 norm before applying the coefficients.

  :param h2_labelled_dataset:
  :return: `h2_m_repr`, a nested Python dict representing the learned decision boundary.
  """
  X_training, get_model_repr = h2_features_matrix(h2_labelled_dataset)
  y_training = h2_label_vector(h2_labelled_dataset)

  proto_clf = sklearn.linear_model.SGDClassifier(
    class_weight={1: 2000.}, ## NOTE this huge imbalance in weights is explained by: (Val, 05 Jun 2020)
    # 1) compensating for class imbalance, and
    # 2) aiming for high-recall, low precision (high tolerance for false positives)
    penalty='l2',
    loss='hinge')
  ## NOTE training is repeated, keeping the classifier achieving the best training fit. (Val, 05 Jun 2020)
  # I have observed that performance varies wildly across training runs,
  # (unsurprising given that our use of SVM is somewhat contrived)
  # and that a good training fit is correlated with a good validation fit,
  # which justifies the approach (one might fear it would lead to overfitting).
  clf = uskl.MyRepeatedTrainingClassifier(proto_clf, n_repeat=30, random_state=583904920)

  clf.fit(X_training, y_training)
  h2_m_repr = get_model_repr(clf.base_estimator.coef_[0, :], clf.base_estimator.intercept_[0])

  h2_m_repr['INFO'] = {}

  if cv_k_folds is not None:
    cv_metrics = {
     'recall': list(cross_val_score(clf, X_training, y_training, cv=cv_k_folds, scoring='recall')),
     'precision': list(cross_val_score(clf, X_training, y_training, cv=cv_k_folds, scoring='precision'))
    }
    h2_m_repr['INFO']['kfold_cv_metrics'] = cv_metrics

  return  h2_m_repr


def train_and_save_h2_linear_classifier_jsongz(h2_trainset_path, h2_learned_model_path, cv_k_folds=None):
  """
  Trains and saves a linear classifier from/to the given file paths.
  Gzipped-json encoding will be used.
  """
  h2_labelled_dataset = uio.read_file_jsongz(h2_trainset_path)
  h2_m_repr = train_h2_linear_classifier(h2_labelled_dataset, cv_k_folds=cv_k_folds)
  uio.write_file_jsongz(h2_m_repr, h2_learned_model_path)


def _dev_manual():
  h2_trainset_path = "./resources/h2-train-set-0.json.gz"
  h2_learned_model_path = "./resources/h2-learned-model-0.json.gz"

  cv_k_folds = 5

  h2_labelled_dataset = uio.read_file_jsongz(h2_trainset_path)
  random.seed(885885)
  random.shuffle(h2_labelled_dataset)

  n_test = 2000
  n_validation = 1000
  n_all = len(h2_labelled_dataset)

  X, get_model_repr = h2_features_matrix(h2_labelled_dataset)
  y = h2_label_vector(h2_labelled_dataset)

  # training_inputs = h2_labelled_dataset[n_test + n_validation: len(h2_labelled_dataset) + 1]
  # len(h2_labelled_dataset)
  # len(training_inputs)

  X_training = X[n_test + n_validation: n_all]
  y_training = y[n_test + n_validation: n_all]

  X_valid = X[n_test: n_test + n_validation]
  y_valid = y[n_test: n_test + n_validation]

  ## https://scikit-learn.org/stable/auto_examples/svm/plot_separating_hyperplane_unbalanced.html#sphx-glr-auto-examples-svm-plot-separating-hyperplane-unbalanced-py
  # model = sklearn.linear_model.SGDClassifier(class_weight={1: 1000.}, penalty='l2', loss='log')
  model = sklearn.linear_model.SGDClassifier(class_weight={1: 2000.}, penalty='l2', loss='hinge')
  # model = sklearn.linear_model.SGDClassifier(class_weight={1: 500.}, penalty='l1', loss='hinge')
  model = uskl.MyRepeatedTrainingClassifier(model, n_repeat=30, random_state=583904920)
  # model = sklearn.ensemble.BaggingClassifier(model, max_samples = 0.75, max_features=0.9)


def _dev_reload():
  import importlib
  importlib.reload(uskl)


def _dev_train_xval():
  {'recall': cross_val_score(clf, X_training, y_training, cv=5, scoring='recall'),
   'precision': cross_val_score(clf, X_training, y_training, cv=5, scoring='precision')}

  model.base_estimator.coef_[0,0:6]
  model.base_estimator.intercept_


def prediction_metrics_summary(model, X_training, y_training, y_valid, X_valid):
  model.fit(X_training, y_training)
  return [
   sklearn.metrics.precision_score(y_valid, model.predict(X_valid)),
   sklearn.metrics.recall_score(y_valid, model.predict(X_valid)),
   sklearn.metrics.confusion_matrix(y_valid, model.predict(X_valid)),
   sklearn.metrics.confusion_matrix(y_training, model.predict(X_training)),
   sklearn.metrics.precision_score(y_training, model.predict(X_training)),
   sklearn.metrics.recall_score(y_training, model.predict(X_training)),
   model.score(X_training, y_training)]

def _dev_misc():
  y_pred = model.predict(X_valid)

  m_repr = get_model_repr(model.base_estimator.coef_[0,:], model.base_estimator.intercept_)

  import math
  def top_entries(d, n):
    l = list(sorted(d.items(), key=lambda item: -math.fabs(item[1])))
    return l[0:n-1]


  h2_m_repr['intercept']
  h2_m_repr['dgms_comment']['non_word']
  h2_m_repr['dgms_parent']['non_word']
  top_entries(m_repr['dgms_comment']['BoW'], 20)
  top_entries(m_repr['dgms_parent']['BoW'], 50)


  [(h2_labelled_dataset[n_test + i], y_pred[i]) for i in range(n_validation) if (h2_labelled_dataset[n_test + i]['dgms_h2_label'] > 0.5)]

  model = sklearn.naive_bayes.MultinomialNB(class_prior=[1,100])

  sklearn.metrics.confusion_matrix(y_valid, model.predict(X_valid))





