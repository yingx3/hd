function[Uw,Us]=spcro(Uw,Us,dt)

    [Uw,Us]=area_x(Uw,Us,dt);

    [Uw,Us]=x_stps(Uw,Us,dt);
