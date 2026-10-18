"""External platform plugin for Hermes v0.22.2."""


def register(ctx):
    from .adapter import register as register_platform
    register_platform(ctx)
