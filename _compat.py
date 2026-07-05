# -*- coding: utf-8 -*-
class cached_property(object):
	"""
	Минимальная реализация cached_property для Python 2.
	В Python 3.8+ есть functools.cached_property, в Python 2 его нет.
	"""
	def __init__(self, func):
		self.func = func
		self.__doc__ = getattr(func, '__doc__')

	def __get__(self, obj, cls):
		if obj is None:
			return self
		value = obj.__dict__[self.func.__name__] = self.func(obj)
		return value
