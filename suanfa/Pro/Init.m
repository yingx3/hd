function[Uw,Us]=Init(zB,zL,hW)

    global z n m hG x y

    hG=hW;hS=zB-zL;z=zL;

    [n,m]=size(z);

    Us=zeros(n,m,3);Us(:,:,1)=hS;

    Uw=zeros(n,m,3);Uw(:,:,1)=hW;

    [x,y]=meshgrid(1:1:m,1:1:n);

