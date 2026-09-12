function[Uw,Us]=spcrp(Uw,Us,dt)

    [Uw,Us]=area_y(Uw,Us,dt);

    [Uw,Us]=y_stps(Uw,Us,dt);