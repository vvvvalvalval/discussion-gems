"""
Utilities related to Scikit-Learn.
"""
import sklearn.base
from sklearn.base import BaseEstimator, ClassifierMixin
from sklearn.utils.multiclass import unique_labels
import sklearn.utils.validation


def train_model_repeatedly(initial_model, X, y, random_state=None, n_repeat=10):
  best_score = -1.
  best_model = None
  im = initial_model
  rs = random_state
  for i in range(n_repeat):
    m1 = sklearn.base.clone(im)
    if rs is not None:
      m1.set_params(random_state=rs)
    m1.fit(X, y)
    score = m1.score(X, y)
    #print(score, best_score)
    if score > best_score:
      best_model = m1
      best_score = score
  return best_model

## Implementation guide: https://scikit-learn.org/stable/developers/develop.html
class MyRepeatedTrainingClassifier(BaseEstimator, ClassifierMixin):
  """
  Wraps a classifier so that it gets trained several times
  and the highest-scoring trained model is retained.

  (Presumably, training the base classifier is non-deterministic!)
  """
  def __init__(self, base_estimator, n_repeat=10, random_state=None):
    self.base_estimator = base_estimator
    self.n_repeat = n_repeat
    self.random_state = random_state

  def fit(self, X, y):
    # Store the classes seen during fit
    self.classes_ = unique_labels(y)
    self.X_ = X
    self.y_ = y

    rs = None
    if self.random_state is not None:
      rs = sklearn.utils.validation.check_random_state(self.random_state)
    self.base_estimator = train_model_repeatedly(self.base_estimator, X, y, n_repeat=self.n_repeat, random_state=rs)
    if self.random_state is not None:
      self.random_state_ = rs
    return self

  def predict(self, X):
    return self.base_estimator.predict(X)

