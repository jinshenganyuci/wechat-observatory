"""WeChat platform plugin using Hermes' official adapter contract."""


def register(ctx):
    from .adapter import register as register_platform
    register_platform(ctx)
