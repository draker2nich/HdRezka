# -*- coding: utf-8 -*-
class LoginRequiredError(Exception):
	def __init__(self): super(LoginRequiredError, self).__init__("Login is required to access this page.")

class FetchFailed(Exception):
	def __init__(self): super(FetchFailed, self).__init__("Failed to fetch stream!")

class CaptchaError(Exception):
	def __init__(self): super(CaptchaError, self).__init__("Failed to bypass captcha!")

class HTTP(Exception):
	def __init__(self, code, message=""): super(HTTP, self).__init__("%s: %s" % (code, message))
