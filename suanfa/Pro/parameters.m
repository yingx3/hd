
function parameters(Par)

    global g bed r tol db nn dx dy Interval

    bed=Par(1);

    nn=Par(2);

    dx=Par(3);dy=Par(4);

    rous=Par(5);

    rouf=Par(6);

    r=rouf/rous;

    Interval=Par(7);

    g=9.8;

    tol=0.5;

    db=0;
