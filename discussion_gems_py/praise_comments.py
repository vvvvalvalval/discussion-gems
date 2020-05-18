import pymc3 as pm
import numpy as np

def lookup_key(dict, k, dflt):
  if k in dict:
    return dict[k]
  else:
    return dflt

def sample_heuristic_precision(hcnts, sample_kwargs):
  """
  Runs samples for Bayesian inference of the parameters of the heuristic model, using PyMC3.

  Will take samples of the following parameters:
  * 'p_H': Pr(h+ | r+) (heuristic recall)
  * 'p_R': Pr(r+)
  * 'q': Pr(r+ | h+) (heuristic precision)

  :param hcnts: 'Heuristic Counts', a dictionary of observed sufficient statistics.
  :return: a PyMC3 trace.
  """
  n_Hp_Rp = hcnts['n_Hp_Rp']
  n_Hn_Rp = hcnts['n_Hn_Rp']
  n_Hp_Rn = hcnts['n_Hp_Rn']
  n_Hn_Rn = hcnts['n_Hn_Rn']

  n1_Hp = lookup_key(hcnts, 'n1_Hp', 0)
  n1_Hp_Rp = lookup_key(hcnts, 'n1_Hp_Rp', 0)

  N = n_Hp_Rp + n_Hn_Rp + n_Hp_Rn + n_Hn_Rn
  n_Rp = n_Hp_Rp + n_Hn_Rp
  n_Rn = n_Hp_Rn + n_Hn_Rn
  n_Hp = n_Hp_Rp + n_Hp_Rn

  P_Hp = lookup_key(hcnts, 'P_Hp', (n_Hp / N))

  with pm.Model() as model:
    p_R = pm.Uniform('p_R', 0., 0.2)
    p_H = pm.Beta('p_H', 1. + 3., 1.)

    #p_H = pm.Beta('p_H', alpha=1., beta=1.)
    #p_R = pm.Beta('p_R', alpha=1., beta=1.) ## FIXME

    pm.Binomial('obs_Rp', N, p_R, observed=n_Rp)
    pm.Binomial('obs_Hp_Rp', n_Rp, p_H, observed=n_Hp_Rp)
    #p_H = pm.Beta('p_H', alpha=(1. + n_Hp_Rp), beta=(1. + n_Hn_Rp))
    #p_R = pm.Beta('p_R', alpha=(1. + n_Rp), beta=(1. + n_Rn))
    q = pm.Deterministic('q', p_H * p_R / P_Hp)
    r1 = pm.Binomial('r1', n1_Hp, q, observed=n1_Hp_Rp)
    #pf = pm.Deterministic('pf', q / p_R)

    #target_positives = 1000
    #M_1000p = pm.NegativeBinomial('M_1000p', target_positives * (1 - q) / q, target_positives)

    trace = pm.sample(**sample_kwargs)  #(10000, tune=5000)

  return trace


def cmt_example():
  obs = {
    'n1_Hp_Rp': 60,
    'n1_Hp': 1000,

    'n_Hp_Rp': 40,
    'n_Hn_Rp': 2,
    'n_Hp_Rn': 715,
    'n_Hn_Rn': 3381
  }

  trace=sample_heuristic_precision(obs, {'draws': 10000, 'tune': 5000})

  pm.plot_posterior(trace, credible_interval=0.94)
  pm.plot_posterior(trace, credible_interval=0.99)

  help(pm.plot_posterior)

  pm.traceplot(trace)
  pm.forestplot(trace)

  q_samples = trace['q']
  np.average(q_samples < 0.03)

